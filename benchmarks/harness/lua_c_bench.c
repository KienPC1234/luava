#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <lua.h>
#include <lauxlib.h>
#include <lualib.h>

static int cmp_double(const void *a, const void *b) {
    double da = *(const double *)a;
    double db = *(const double *)b;
    return (da > db) - (da < db);
}

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

static char *read_file(const char *path, size_t *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror("fopen");
        exit(1);
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = (char *)malloc((size_t)sz + 1);
    if (!buf) {
        fprintf(stderr, "oom\n");
        exit(1);
    }
    size_t got = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    buf[got] = '\0';
    *out_len = got;
    return buf;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <file.lua> <warm> <iters> [exec|e2e]\n", argv[0]);
        return 1;
    }
    const char *file = argv[1];
    int warm = atoi(argv[2]);
    int iters = atoi(argv[3]);
    const char *mode = (argc > 4) ? argv[4] : "exec";
    int exec_mode = strcmp(mode, "e2e") != 0;

    size_t len = 0;
    char *code = read_file(file, &len);

    lua_State *L = luaL_newstate();
    luaL_openlibs(L);

    if (exec_mode) {
        if (luaL_loadbuffer(L, code, len, file) != LUA_OK) {
            fprintf(stderr, "load error: %s\n", lua_tostring(L, -1));
            return 2;
        }
    }

    double cold_t0 = now_ms();
    if (exec_mode) {
        lua_pushvalue(L, -1);
    } else if (luaL_loadbuffer(L, code, len, file) != LUA_OK) {
        fprintf(stderr, "load error: %s\n", lua_tostring(L, -1));
        return 2;
    }
    if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
        fprintf(stderr, "exec error: %s\n", lua_tostring(L, -1));
        return 3;
    }
    double cold_ms = now_ms() - cold_t0;

    for (int i = 0; i < warm - 1; i++) {
        if (exec_mode) {
            lua_pushvalue(L, -1);
        } else if (luaL_loadbuffer(L, code, len, file) != LUA_OK) {
            fprintf(stderr, "load error: %s\n", lua_tostring(L, -1));
            return 2;
        }
        if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
            fprintf(stderr, "exec error: %s\n", lua_tostring(L, -1));
            return 3;
        }
    }

    double *samples = (double *)calloc((size_t)iters, sizeof(double));
    double best = 1e18, sum = 0.0;
    for (int i = 0; i < iters; i++) {
        double t0 = now_ms();
        if (exec_mode) {
            lua_pushvalue(L, -1);
        } else if (luaL_loadbuffer(L, code, len, file) != LUA_OK) {
            fprintf(stderr, "load error: %s\n", lua_tostring(L, -1));
            return 2;
        }
        if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
            fprintf(stderr, "exec error: %s\n", lua_tostring(L, -1));
            return 3;
        }
        double ms = now_ms() - t0;
        samples[i] = ms;
        if (ms < best) best = ms;
        sum += ms;
    }

    qsort(samples, (size_t)iters, sizeof(double), cmp_double);
    double median = (iters % 2 == 1)
            ? samples[iters / 2]
            : (samples[iters / 2 - 1] + samples[iters / 2]) / 2.0;
    double p25 = samples[iters / 4];
    double p75 = samples[(iters * 3) / 4];

    const char *base = strrchr(file, '/');
    base = base ? base + 1 : file;
    printf("%-28s best=%8.2f avg=%8.2f median=%8.2f iqr=%6.2f cold=%8.2f mode=%s lua=%s ms\n",
           base, best, sum / iters, median, p75 - p25, cold_ms, mode, LUA_RELEASE);

    free(samples);
    free(code);
    lua_close(L);
    return 0;
}
