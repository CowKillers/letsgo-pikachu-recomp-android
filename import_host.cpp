// Host driver for the project's own importer (nximport): NSP + prod.keys ->
// <out>/extracted/romfs/ + <out>/extracted/romfs.bin + <out>/extracted/exefs/
// Same code the launcher runs on the phone, compiled for the host, so the
// Pikachu datapack is built from the same extraction the phone would do.
// Prints only names, counts and sizes — never key values.
#include "nximport.h"
#include <fcntl.h>
#include <cstdio>
#include <unistd.h>
int main(int argc, char** argv) {
    if (argc < 4) { fprintf(stderr, "usage: import_host <prod.keys> <nsp> <outdir>\n"); return 2; }
    int kfd = open(argv[1], O_RDONLY); if (kfd < 0) { perror("keys"); return 2; }
    int nfd = open(argv[2], O_RDONLY); if (nfd < 0) { perror("nsp"); return 2; }
    nxi::Options opt; opt.out_dir = argv[3];
    opt.title_id = 0x010003F003A34000ULL;  // Let's Go Pikachu
    opt.folder_only = true;  // keep loose romfs folder (for the title movies)
    opt.on_log = [](const std::string& s) { printf("%s\n", s.c_str()); fflush(stdout); };
    opt.on_progress = [](const nxi::Progress& p) {
        static uint64_t last = 0;
        if (p.done - last >= (512ULL << 20) || p.done == p.total) {
            printf("  [%s] %llu / %llu MB %s\n", p.phase.c_str(),
                   (unsigned long long)(p.done >> 20), (unsigned long long)(p.total >> 20), p.item.c_str());
            fflush(stdout); last = p.done;
        }
    };
    std::vector<nxi::InputFile> in = {{"prod.keys", kfd}, {"Pokemon Lets Go Pikachu.nsp", nfd}};
    std::string err; int rc = nxi::run_import(in, opt, err);
    printf("IMPORT_EXIT=%d %s\n", rc, err.c_str());
    return rc;
}
