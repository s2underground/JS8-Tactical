// Feeds arbitrary bytes, as a request, to the VOACAP engine's C entry points (android/voacap/rust):
// the point prediction and the area grid. Built against a Rust library compiled with
// `--cfg fuzzing`, so a panic aborts and is reported as a crash instead of being turned into an
// error. See scripts/fuzz-voacap.sh.
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

char *js8voacap_run(const char *request, int *ok);
void js8voacap_free(char *s);
unsigned char *js8voacap_area(const char *request, size_t *len, char **err);
void js8voacap_free_bytes(unsigned char *p, size_t len);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size > 4096) return 0;
    char *req = malloc(size + 1);
    if (req == NULL) return 0;
    memcpy(req, data, size);
    req[size] = '\0';  // an embedded NUL just ends the request early, as it would from C

    int ok = -1;
    char *out = js8voacap_run(req, &ok);
    if (out != NULL) {
        if (ok != 0 && ok != 1) abort();  // the flag is always set
        js8voacap_free(out);
    }

    size_t len = 0;
    char *err = NULL;
    unsigned char *grid = js8voacap_area(req, &len, &err);
    if (grid != NULL) {
        if (len < 28 || err != NULL) abort();  // a grid has its header, and no error with it
        js8voacap_free_bytes(grid, len);
    } else if (err != NULL) {
        js8voacap_free(err);
    }
    free(req);
    return 0;
}
