// RomFS image from a folder of game files (runtime/romfs_build.c). The game reads its data through one raw RomFS
// image (romfs.bin, runtime/services/fs.c); players bring their own game files, and the start screen builds the image
// from their romfs/ folder.
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Writes the RomFS image of the folder `dir` to `out`. progress (may be NULL): bytes of file data written so far and
// the total, now and then. false = failed (why: a short message).
bool romfs_build(const char *dir, const char *out, void (*progress)(uint64_t done, uint64_t total), char *why,
                 size_t why_size);

// One file of a RomFS image, for the direct build: its path in the image and
// the offset of its data in the source image's data region.
typedef struct {
    const char *path;  // relative path, e.g. "bin/movies/EEVEE_GO/title_female.webm"
    uint64_t size;
    uint64_t src_off;  // offset of the file's data in the source data region
} RomfsDirectFile;

// Reads n bytes of source data at src_off into dst; false = read error.
typedef bool (*romfs_read_fn)(void *ctx, uint64_t src_off, void *dst, size_t n);

// Builds the RomFS image from the file list, reading file bytes through
// `read` instead of from a folder. The image layout is the same as
// romfs_build() (0x50 header, data from 0x200, tables after the data) except
// the file data is laid out in src_off order — one sequential pass over the
// source — instead of table order, so the resulting image has a different
// sha256. The tables are identical to romfs_build() for the same file set
// (same names, same table order); only the data_off fields and the physical
// data order differ. `dirs` is the list of directory paths in the image
// (relative, "" excluded); empty directories come from it. Atomic (.part +
// rename), as romfs_build().
bool romfs_build_direct(const RomfsDirectFile *files, int nfiles, const char **dirs, int ndirs,
                        romfs_read_fn read, void *ctx, const char *out,
                        void (*progress)(uint64_t done, uint64_t total), char *why, size_t why_size);

// Bytes of file data under dir (the image is about this plus its tables).
uint64_t romfs_dir_bytes(const char *dir);

// Copies the folder tree src to dst (hard links where the file system allows: no second copy of 6 GB). progress as
// above. false = failed.
bool romfs_copy_tree(const char *src, const char *dst, void (*progress)(uint64_t done, uint64_t total));

#ifdef __cplusplus
}
#endif
