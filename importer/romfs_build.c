// RomFS image builder (romfs_build.h). Layout as the game's own image: a 0x50-byte header, file data
// from 0x200 (each file 16-byte aligned), then the directory hash table, directory entries, file hash table and file
// entries. Entries are found by hash (the game's RomFS code walks the tables itself), so the order inside them only
// has to be consistent: directories and files are sorted by name, children before siblings' children.
#include "romfs_build.h"


#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __APPLE__
#include <sys/clonefile.h>
#endif
#ifdef _WIN32
#include <windows.h>
#endif

#define EMPTY 0xFFFFFFFFu
#define DATA_START 0x200u

typedef struct Dir Dir;
typedef struct {
    char *name, *path;
    uint64_t size, data_off;
    uint64_t src_off;  // offset in the source image's data region (direct build only; 0 for a folder build)
    uint32_t off;  // entry offset in the file table
    Dir *parent;
} File;
struct Dir {
    char *name, *path;
    Dir *parent;
    Dir **dirs;
    File **files;
    int ndirs, nfiles;
    uint32_t off;  // entry offset in the directory table
};

static int by_name_dir(const void *a, const void *b) { return strcmp((*(Dir *const *)a)->name, (*(Dir *const *)b)->name); }
static int by_name_file(const void *a, const void *b) { return strcmp((*(File *const *)a)->name, (*(File *const *)b)->name); }

static Dir *scan(const char *path, const char *name, Dir *parent, int depth) {
    if (depth > 32) return NULL;
    DIR *d = opendir(path);
    if (!d) return NULL;
    Dir *dir = calloc(1, sizeof *dir);
    dir->name = strdup(name), dir->path = strdup(path), dir->parent = parent;
    int cd = 0, cf = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;  // (".", "..", and hidden files such as .DS_Store aren't the game's)
        size_t n = strlen(path) + strlen(e->d_name) + 2;
        char *p = malloc(n);
        snprintf(p, n, "%s/%s", path, e->d_name);
        struct stat st;
        if (stat(p, &st) != 0) { free(p); continue; }
        if (S_ISDIR(st.st_mode)) {
            Dir *c = scan(p, e->d_name, dir, depth + 1);
            free(p);
            if (!c) continue;
            if (dir->ndirs == cd) dir->dirs = realloc(dir->dirs, sizeof *dir->dirs * (cd = cd ? cd * 2 : 8));
            dir->dirs[dir->ndirs++] = c;
        } else if (S_ISREG(st.st_mode)) {
            File *f = calloc(1, sizeof *f);
            f->name = strdup(e->d_name), f->path = p, f->size = (uint64_t)st.st_size, f->parent = dir;
            if (dir->nfiles == cf) dir->files = realloc(dir->files, sizeof *dir->files * (cf = cf ? cf * 2 : 16));
            dir->files[dir->nfiles++] = f;
        } else {
            free(p);
        }
    }
    closedir(d);
    if (dir->ndirs) qsort(dir->dirs, dir->ndirs, sizeof *dir->dirs, by_name_dir);
    if (dir->nfiles) qsort(dir->files, dir->nfiles, sizeof *dir->files, by_name_file);
    return dir;
}

static void free_tree(Dir *d) {
    if (!d) return;
    for (int i = 0; i < d->ndirs; i++) free_tree(d->dirs[i]);
    for (int i = 0; i < d->nfiles; i++) free(d->files[i]->name), free(d->files[i]->path), free(d->files[i]);
    free(d->dirs), free(d->files), free(d->name), free(d->path), free(d);
}

// flat lists, in table order
typedef struct { Dir **dirs; File **files; int nd, nf, cd, cf; } Lists;
static void flatten(Dir *d, Lists *l) {
    for (int i = 0; i < d->nfiles; i++) {
        if (l->nf == l->cf) l->files = realloc(l->files, sizeof *l->files * (l->cf = l->cf ? l->cf * 2 : 256));
        l->files[l->nf++] = d->files[i];
    }
    for (int i = 0; i < d->ndirs; i++) {
        if (l->nd == l->cd) l->dirs = realloc(l->dirs, sizeof *l->dirs * (l->cd = l->cd ? l->cd * 2 : 64));
        l->dirs[l->nd++] = d->dirs[i];
    }
    for (int i = 0; i < d->ndirs; i++) flatten(d->dirs[i], l);
}

// the RomFS name hash and bucket count (as the game's RomFS code computes them)
static uint32_t name_hash(uint32_t parent, const char *name) {
    uint32_t h = parent ^ 123456789u;
    for (const unsigned char *p = (const unsigned char *)name; *p; p++) h = ((h >> 5) | (h << 27)) ^ *p;
    return h;
}
static uint32_t bucket_count(uint32_t n) {
    if (n < 3) return 3;
    if (n < 19) return n | 1;
    uint32_t c = n;
    while (c % 2 == 0 || c % 3 == 0 || c % 5 == 0 || c % 7 == 0 || c % 11 == 0 || c % 13 == 0 || c % 17 == 0) c++;
    return c;
}

static uint32_t align4(uint32_t x) { return (x + 3) & ~3u; }
static uint64_t align16(uint64_t x) { return (x + 15) & ~15ull; }
static void put32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void put64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }

uint64_t romfs_dir_bytes(const char *dir) {
    Dir *t = scan(dir, "", NULL, 0);
    if (!t) return 0;
    Lists l = {0};
    flatten(t, &l);
    uint64_t n = 0;
    for (int i = 0; i < l.nf; i++) n += l.files[i]->size;
    free(l.dirs), free(l.files);
    free_tree(t);
    return n;
}

// One file's bytes into the image (the data writer used by build_image).
typedef bool (*romfs_copy_fn)(void *ctx, File *f, FILE *out, uint8_t *buf, size_t bufsize, uint64_t *done,
                              uint64_t total, void (*progress)(uint64_t, uint64_t));

static bool copy_data_disk(void *ctx, File *f, FILE *out, uint8_t *buf, size_t bufsize, uint64_t *done,
                           uint64_t total, void (*progress)(uint64_t, uint64_t)) {
    (void)ctx;
    FILE *in = fopen(f->path, "rb");
    if (!in) return false;
    uint64_t left = f->size;
    while (left) {
        size_t want = left < bufsize ? (size_t)left : bufsize;
        size_t got = fread(buf, 1, want, in);
        if (got != want || fwrite(buf, 1, got, out) != got) { fclose(in); return false; }
        left -= got;
        *done += got;
        if (progress) progress(*done, total);
    }
    fclose(in);
    return true;
}

// The direct build's data writer: the file's bytes come from the source
// image through `read` (set in the context), at the file's src_off.
typedef struct {
    romfs_read_fn read;
    void *read_ctx;
} DirectCopyCtx;

static bool copy_data_direct(void *ctx, File *f, FILE *out, uint8_t *buf, size_t bufsize, uint64_t *done,
                             uint64_t total, void (*progress)(uint64_t, uint64_t)) {
    DirectCopyCtx *c = (DirectCopyCtx *)ctx;
    uint64_t left = f->size, pos = 0;
    while (left) {
        size_t want = left < bufsize ? (size_t)left : bufsize;
        if (!c->read(c->read_ctx, f->src_off + pos, buf, want)) return false;
        if (fwrite(buf, 1, want, out) != want) return false;
        left -= want, pos += want;
        *done += want;
        if (progress) progress(*done, total);
    }
    return true;
}

// The shared image writer: tables in flatten() order (as romfs_build() has
// always built them), file data in `data_order` (whose data_off fields are
// assigned here, in that order). `copy_one` supplies each file's bytes.
// `l` and its tree stay owned by the caller.
static bool build_image(Lists *l, const char *out_path, void (*progress)(uint64_t, uint64_t), char *why,
                        size_t why_size, File **data_order, romfs_copy_fn copy_one, void *copy_ctx) {
    #define FAIL(...) do { if (why) snprintf(why, why_size, __VA_ARGS__); goto fail; } while (0)
    bool ok = false;
    FILE *out = NULL;
    char *part_path = NULL;
    uint8_t *dtab = NULL, *ftab = NULL, *buf = NULL;
    uint32_t *dbuckets = NULL, *fbuckets = NULL;

    // entry offsets
    uint32_t dsize = 0, fsize = 0;
    for (int i = 0; i < l->nd; i++) l->dirs[i]->off = dsize, dsize += 0x18 + align4((uint32_t)strlen(l->dirs[i]->name));
    for (int i = 0; i < l->nf; i++) l->files[i]->off = fsize, fsize += 0x20 + align4((uint32_t)strlen(l->files[i]->name));
    // file data offsets (relative to the data start), each 16-byte aligned,
    // assigned in data_order (table order for a folder build, source-offset
    // order for a direct build).
    uint64_t data = 0, total = 0;
    for (int i = 0; i < l->nf; i++) {
        File *f = data_order[i];
        data = align16(data);
        f->data_off = data;
        data += f->size;
        total += f->size;
    }

    uint32_t nbd = bucket_count((uint32_t)l->nd), nbf = bucket_count((uint32_t)l->nf);
    dbuckets = malloc(4 * nbd), fbuckets = malloc(4 * nbf);
    for (uint32_t i = 0; i < nbd; i++) dbuckets[i] = EMPTY;
    for (uint32_t i = 0; i < nbf; i++) fbuckets[i] = EMPTY;
    dtab = calloc(1, dsize), ftab = calloc(1, fsize);
    for (int i = 0; i < l->nd; i++) {
        Dir *d = l->dirs[i];
        uint8_t *e = dtab + d->off;
        uint32_t parent = d->parent ? d->parent->off : 0, nl = (uint32_t)strlen(d->name);
        uint32_t sib = EMPTY;
        if (d->parent)
            for (int k = 0; k + 1 < d->parent->ndirs; k++)
                if (d->parent->dirs[k] == d) sib = d->parent->dirs[k + 1]->off;
        uint32_t h = name_hash(parent, d->name) % nbd;
        put32(e, parent);
        put32(e + 4, sib);
        put32(e + 8, d->ndirs ? d->dirs[0]->off : EMPTY);
        put32(e + 12, d->nfiles ? d->files[0]->off : EMPTY);
        put32(e + 16, dbuckets[h]);
        put32(e + 20, nl);
        memcpy(e + 0x18, d->name, nl);
        dbuckets[h] = d->off;
    }
    for (int i = 0; i < l->nf; i++) {
        File *f = l->files[i];
        uint8_t *e = ftab + f->off;
        uint32_t nl = (uint32_t)strlen(f->name), sib = EMPTY;
        for (int k = 0; k + 1 < f->parent->nfiles; k++)
            if (f->parent->files[k] == f) sib = f->parent->files[k + 1]->off;
        uint32_t h = name_hash(f->parent->off, f->name) % nbf;
        put32(e, f->parent->off);
        put32(e + 4, sib);
        put64(e + 8, f->data_off);
        put64(e + 16, f->size);
        put32(e + 24, fbuckets[h]);
        put32(e + 28, nl);
        memcpy(e + 0x20, f->name, nl);
        fbuckets[h] = f->off;
    }

    // header: tables after the file data (4-byte aligned)
    uint64_t dhash_off = (DATA_START + data + 3) & ~3ull;
    uint64_t dmeta_off = dhash_off + 4ull * nbd, fhash_off = dmeta_off + dsize, fmeta_off = fhash_off + 4ull * nbf;
    uint8_t hdr[DATA_START] = {0};
    uint64_t hv[10] = {0x50, dhash_off, 4ull * nbd, dmeta_off, dsize, fhash_off, 4ull * nbf, fmeta_off, fsize, DATA_START};
    memcpy(hdr, hv, sizeof hv);

    // (v0114) Atomic build: write to <out>.part and rename on success. A
    // killed build used to leave a partial romfs.bin in place, and the
    // importer accepted it by existence alone (03/11: 4.8 MB short, the game
    // crashed at boot). Now a killed build leaves only a .part behind, and
    // the previous .bin (if any) is untouched by a failed rebuild.
    part_path = malloc(strlen(out_path) + 6);
    if (!part_path) FAIL("out of memory");
    sprintf(part_path, "%s.part", out_path);
    out = fopen(part_path, "wb");
    if (!out) FAIL("can't write %s", out_path);
    if (fwrite(hdr, 1, DATA_START, out) != DATA_START) FAIL("write failed (disk full?)");
    size_t bufsize = 4u << 20;
    buf = malloc(bufsize);
    uint64_t pos = 0, done = 0;
    static const uint8_t zero[16];
    for (int i = 0; i < l->nf; i++) {
        File *f = data_order[i];
        if (f->data_off > pos && fwrite(zero, 1, (size_t)(f->data_off - pos), out) != f->data_off - pos)
            FAIL("write failed (disk full?)");
        if (!copy_one(copy_ctx, f, out, buf, bufsize, &done, total, progress)) FAIL("can't copy %s", f->path);
        pos = f->data_off + f->size;
    }
    uint64_t tail = dhash_off - DATA_START - pos;
    if (tail && fwrite(zero, 1, (size_t)tail, out) != tail) FAIL("write failed (disk full?)");
    if (fwrite(dbuckets, 4, nbd, out) != nbd || fwrite(dtab, 1, dsize, out) != dsize ||
        fwrite(fbuckets, 4, nbf, out) != nbf || fwrite(ftab, 1, fsize, out) != fsize)
        FAIL("write failed (disk full?)");
    if (fclose(out) != 0) { out = NULL; FAIL("write failed (disk full?)"); }
    out = NULL;
    if (rename(part_path, out_path) != 0) FAIL("can't finish %s", out_path);
    ok = true;
fail:
    if (out) fclose(out);
    if (!ok && part_path) remove(part_path);
    free(part_path);
    free(buf), free(dtab), free(ftab), free(dbuckets), free(fbuckets);
    return ok;
    #undef FAIL
}

bool romfs_build(const char *dir, const char *out_path, void (*progress)(uint64_t, uint64_t), char *why, size_t why_size) {
    Dir *root = scan(dir, "", NULL, 0);
    if (!root) {
        if (why) snprintf(why, why_size, "can't read %s", dir);
        return false;
    }
    Lists l = {0};
    // table order: root, then each directory's subdirectories as flatten() lists them; files likewise
    if (l.nd == l.cd) l.dirs = realloc(l.dirs, sizeof *l.dirs * (l.cd = 64));
    l.dirs[l.nd++] = root;
    flatten(root, &l);
    if (!l.nf) {
        if (why) snprintf(why, why_size, "no files in %s", dir);
        free(l.dirs), free(l.files);
        free_tree(root);
        return false;
    }
    // A folder build writes the data in table order, exactly as before.
    bool ok = build_image(&l, out_path, progress, why, why_size, l.files, copy_data_disk, NULL);
    free(l.dirs), free(l.files);
    free_tree(root);
    return ok;
}

// ---- the direct build: the tree from a file list, not from a folder ----

// Finds (creating parents as needed) the directory at relative path `rel`
// ("" = the root itself). `rel` uses '/' separators, no leading/trailing '/'.
static Dir *tree_dir(Dir *root, const char *rel) {
    if (!rel || !*rel) return root;
    Dir *d = root;
    const char *p = rel;
    while (*p) {
        const char *slash = strchr(p, '/');
        size_t n = slash ? (size_t)(slash - p) : strlen(p);
        Dir *child = NULL;
        for (int i = 0; i < d->ndirs; i++)
            if (strlen(d->dirs[i]->name) == n && memcmp(d->dirs[i]->name, p, n) == 0) { child = d->dirs[i]; break; }
        if (!child) {
            child = calloc(1, sizeof *child);
            child->name = strndup(p, n);
            size_t pn = strlen(d->path) + n + 2;
            child->path = malloc(pn);
            snprintf(child->path, pn, "%s/%s", d->path, child->name);
            child->parent = d;
            d->dirs = realloc(d->dirs, sizeof *d->dirs * (d->ndirs + 1));
            d->dirs[d->ndirs++] = child;
        }
        d = child;
        p = slash ? slash + 1 : p + n;
    }
    return d;
}

static void sort_tree(Dir *d) {
    if (d->ndirs) qsort(d->dirs, d->ndirs, sizeof *d->dirs, by_name_dir);
    if (d->nfiles) qsort(d->files, d->nfiles, sizeof *d->files, by_name_file);
    for (int i = 0; i < d->ndirs; i++) sort_tree(d->dirs[i]);
}

static int by_src_off(const void *a, const void *b) {
    uint64_t x = (*(File *const *)a)->src_off, y = (*(File *const *)b)->src_off;
    return x < y ? -1 : x > y ? 1 : 0;
}

bool romfs_build_direct(const RomfsDirectFile *files, int nfiles, const char **dirs, int ndirs,
                        romfs_read_fn read, void *read_ctx, const char *out_path,
                        void (*progress)(uint64_t, uint64_t), char *why, size_t why_size) {
    if (!files || nfiles <= 0 || !read) {
        if (why) snprintf(why, why_size, "no files");
        return false;
    }
    Dir *root = calloc(1, sizeof *root);
    root->name = strdup(""), root->path = strdup("");
    for (int i = 0; i < ndirs; i++) tree_dir(root, dirs[i]);
    for (int i = 0; i < nfiles; i++) {
        const char *slash = strrchr(files[i].path, '/');
        char *dname;
        const char *fname;
        if (slash) {
            dname = strndup(files[i].path, (size_t)(slash - files[i].path));
            fname = slash + 1;
        } else {
            dname = strdup("");
            fname = files[i].path;
        }
        Dir *parent = tree_dir(root, dname);
        free(dname);
        File *f = calloc(1, sizeof *f);
        f->name = strdup(fname), f->path = strdup(files[i].path);
        f->size = files[i].size, f->src_off = files[i].src_off, f->parent = parent;
        parent->files = realloc(parent->files, sizeof *parent->files * (parent->nfiles + 1));
        parent->files[parent->nfiles++] = f;
    }
    sort_tree(root);
    Lists l = {0};
    if (l.nd == l.cd) l.dirs = realloc(l.dirs, sizeof *l.dirs * (l.cd = 64));
    l.dirs[l.nd++] = root;
    flatten(root, &l);
    if (l.nf != nfiles) {
        if (why) snprintf(why, why_size, "file list mismatch (%d files, %d in the tree)", nfiles, l.nf);
        free(l.dirs), free(l.files);
        free_tree(root);
        return false;
    }
    // The data is written in source-offset order: one sequential pass over
    // the source image, each chunk loaded once. (Reading in table/name order
    // would thrash the source's 4 MiB cache and cancel the gain.)
    File **order = malloc(sizeof *order * (size_t)l.nf);
    memcpy(order, l.files, sizeof *order * (size_t)l.nf);
    qsort(order, (size_t)l.nf, sizeof *order, by_src_off);
    DirectCopyCtx dcc = {read, read_ctx};
    bool ok = build_image(&l, out_path, progress, why, why_size, order, copy_data_direct, &dcc);
    free(order);
    free(l.dirs), free(l.files);
    free_tree(root);
    return ok;
}

// ------------------------------------------------------------------ copying the player's folder
static bool copy_file(const char *src, const char *dst, uint64_t size, uint64_t *done, uint64_t total,
                      void (*progress)(uint64_t, uint64_t)) {
    remove(dst);
#ifdef __APPLE__
    if (clonefile(src, dst, 0) == 0) goto linked;  // (APFS: a copy-on-write clone, instant)
#endif
#ifdef _WIN32
    if (CreateHardLinkA(dst, src, NULL)) goto linked;  // (same NTFS volume: a hard link)
#else
    if (link(src, dst) == 0) goto linked;  // (same volume: a hard link)
#endif
    {
        FILE *in = fopen(src, "rb"), *out = in ? fopen(dst, "wb") : NULL;
        bool ok = in && out;
        static uint8_t buf[1 << 20];
        while (ok) {
            size_t n = fread(buf, 1, sizeof buf, in);
            // (v0114) fread returning 0 is EOF only when !ferror: a mid-file
            // read error used to truncate the copy in silence and return true.
            if (!n) { if (ferror(in)) ok = false; break; }
            if (fwrite(buf, 1, n, out) != n) ok = false;
            *done += n;
            if (progress) progress(*done, total);
        }
        if (in) fclose(in);
        if (out && fclose(out) != 0) ok = false;
        return ok;
    }
linked:
    *done += size;
    if (progress) progress(*done, total);
    return true;
}

static bool copy_dir(Dir *d, const char *dst, uint64_t *done, uint64_t total, void (*progress)(uint64_t, uint64_t)) {
    mkdir(dst, 0755);
    for (int i = 0; i < d->nfiles; i++) {
        size_t n = strlen(dst) + strlen(d->files[i]->name) + 2;
        char *p = malloc(n);
        snprintf(p, n, "%s/%s", dst, d->files[i]->name);
        bool ok = copy_file(d->files[i]->path, p, d->files[i]->size, done, total, progress);
        free(p);
        if (!ok) return false;
    }
    for (int i = 0; i < d->ndirs; i++) {
        size_t n = strlen(dst) + strlen(d->dirs[i]->name) + 2;
        char *p = malloc(n);
        snprintf(p, n, "%s/%s", dst, d->dirs[i]->name);
        bool ok = copy_dir(d->dirs[i], p, done, total, progress);
        free(p);
        if (!ok) return false;
    }
    return true;
}

bool romfs_copy_tree(const char *src, const char *dst, void (*progress)(uint64_t, uint64_t)) {
    Dir *t = scan(src, "", NULL, 0);
    if (!t) return false;
    Lists l = {0};
    flatten(t, &l);
    uint64_t total = 0, done = 0;
    for (int i = 0; i < l.nf; i++) total += l.files[i]->size;
    bool ok = copy_dir(t, dst, &done, total, progress);
    free(l.dirs), free(l.files);
    free_tree(t);
    return ok;
}
