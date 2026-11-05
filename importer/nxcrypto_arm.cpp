// ARMv8 Cryptography Extensions (AES/SHA-256/CRC-32) for the on-device importer.
// Compiled ONLY for aarch64, with -march=armv8-a+crypto+crc (see build-importer.sh);
// every entry point here is called only after crypto_hw_features() (HWCAP) says
// the CPU has the feature, and the software versions in nxcrypto.cpp remain the
// fallback. Same algorithms, same results — no byte ever changes, only the speed.
//
// Validation: crypto_self_test() (nxcrypto.cpp) exercises all three paths
// (AES-CTR NIST vectors, SHA-256 multi-block vectors, CRC-32 chaining) and runs
// at the start of every import, before any data is written — so a wrong
// hardware path fails the import loudly instead of corrupting anything.
// The host (x86_64) never compiles this file, so it cannot validate the
// intrinsics; it validates the software fallback and the whole pipeline.
#if defined(__aarch64__)

#include <arm_acle.h>
#include <arm_neon.h>

#include <cstdint>
#include <cstring>

#include "nxcrypto.h"

namespace nxi {

namespace {

// ---- AES ----

// The 11 round keys, 16 bytes each, in memory order (Aes128::round_keys()).
// The AES instructions take the state and the round key as byte vectors
// loaded in memory order, which is exactly how the key schedule is stored.

inline uint8x16_t aes_block(const uint8x16_t rk[11], uint8x16_t s) {
    s = vaeseq_u8(s, rk[0]);
    for (int r = 1; r < 10; r++) s = vaesmcq_u8(vaeseq_u8(s, rk[r]));
    s = vaeseq_u8(s, rk[10]);
    return s;
}

}  // namespace

void aes_ctr_hw(const Aes128& k, const uint8_t iv[16], uint8_t* data, size_t n) {
    uint8x16_t rk[11];
    const uint8_t* rkb = k.round_keys();
    for (int r = 0; r < 11; r++) rk[r] = vld1q_u8(rkb + 16 * r);
    // The counter is the 16-byte block iv, incremented as a big-endian
    // 128-bit number; in practice only the low 64 bits (iv[8..15], the block
    // index) ever move — a chunk here is at most 4 MiB — so incrementing the
    // low 64 bits is identical to the full 128-bit increment.
    uint64_t ctr = 0;
    for (int i = 0; i < 8; i++) ctr = (ctr << 8) | iv[8 + i];
    uint8_t upper[8];
    std::memcpy(upper, iv, 8);
    auto make_ctr = [&](uint64_t c, uint8_t out[16]) {
        std::memcpy(out, upper, 8);
        for (int i = 0; i < 8; i++) out[8 + i] = uint8_t(c >> (56 - 8 * i));
    };
    uint8_t* p = data;
    while (n >= 64) {
        uint8_t c0[16], c1[16], c2[16], c3[16];
        make_ctr(ctr, c0);
        make_ctr(ctr + 1, c1);
        make_ctr(ctr + 2, c2);
        make_ctr(ctr + 3, c3);
        uint8x16_t e0 = aes_block(rk, vld1q_u8(c0));
        uint8x16_t e1 = aes_block(rk, vld1q_u8(c1));
        uint8x16_t e2 = aes_block(rk, vld1q_u8(c2));
        uint8x16_t e3 = aes_block(rk, vld1q_u8(c3));
        vst1q_u8(p, veorq_u8(vld1q_u8(p), e0));
        vst1q_u8(p + 16, veorq_u8(vld1q_u8(p + 16), e1));
        vst1q_u8(p + 32, veorq_u8(vld1q_u8(p + 32), e2));
        vst1q_u8(p + 48, veorq_u8(vld1q_u8(p + 48), e3));
        p += 64, n -= 64, ctr += 4;
    }
    while (n >= 16) {
        uint8_t c[16];
        make_ctr(ctr, c);
        vst1q_u8(p, veorq_u8(vld1q_u8(p), aes_block(rk, vld1q_u8(c))));
        p += 16, n -= 16, ctr++;
    }
    if (n) {
        uint8_t c[16], ks[16];
        make_ctr(ctr, c);
        vst1q_u8(ks, aes_block(rk, vld1q_u8(c)));
        for (size_t i = 0; i < n; i++) p[i] ^= ks[i];
    }
}

// ---- SHA-256 ----

namespace {

const uint32_t kSha256K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
    0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
    0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

// One SHA-256 compression of a 64-byte block into the state h[8].
// Canonical ARMv8 pattern (SHA-Intrinsics/mbedtls): the state is two
// uint32x4_t (ABCD, EFGH), the message is four vectors (W0-3..W12-15,
// byte-reversed from the little-endian load), and each group of 4 rounds
// does: wk = MSG + K; MSG = SU0(MSG, next); STATE0 = H(STATE0, STATE1, wk);
// STATE1 = H2(STATE1, old STATE0, wk); MSG = SU1(MSG, +2, +3).
// The last 4 groups (rounds 48-63) need no schedule update (W64+ does not exist).
void sha256_block_hw(uint32_t h[8], const uint8_t block[64]) {
    uint32x4_t s0 = vld1q_u32(h);
    uint32x4_t s1 = vld1q_u32(h + 4);
    const uint32x4_t o0 = s0, o1 = s1;
    uint32x4_t m[4];
    m[0] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block)));
    m[1] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 16)));
    m[2] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 32)));
    m[3] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 48)));
    for (int g = 0; g < 16; g++) {
        int i = g & 3;
        uint32x4_t wk = vaddq_u32(m[i], vld1q_u32(kSha256K + 4 * g));
        if (g < 12) m[i] = vsha256su0q_u32(m[i], m[(i + 1) & 3]);
        uint32x4_t tmp = s0;
        s0 = vsha256hq_u32(s0, s1, wk);
        s1 = vsha256h2q_u32(s1, tmp, wk);
        if (g < 12) m[i] = vsha256su1q_u32(m[i], m[(i + 2) & 3], m[(i + 3) & 3]);
    }
    s0 = vaddq_u32(s0, o0);
    s1 = vaddq_u32(s1, o1);
    vst1q_u32(h, s0);
    vst1q_u32(h + 4, s1);
}

}  // namespace

void sha256_hw(const void* data, size_t n, uint8_t out[32]) {
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                     0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    const uint8_t* p = static_cast<const uint8_t*>(data);
    size_t total = n;
    while (n >= 64) {
        sha256_block_hw(h, p);
        p += 64, n -= 64;
    }
    uint8_t tail[128];
    size_t t = n;
    std::memcpy(tail, p, t);
    tail[t++] = 0x80;
    uint64_t bits = uint64_t(total) * 8;
    if (t > 56) {
        std::memset(tail + t, 0, 128 - t);
        for (int i = 0; i < 8; i++) tail[120 + i] = uint8_t(bits >> (56 - 8 * i));
        sha256_block_hw(h, tail);
        sha256_block_hw(h, tail + 64);
    } else {
        std::memset(tail + t, 0, 64 - t);
        for (int i = 0; i < 8; i++) tail[56 + i] = uint8_t(bits >> (56 - 8 * i));
        sha256_block_hw(h, tail);
    }
    for (int i = 0; i < 8; i++) {
        out[4 * i] = uint8_t(h[i] >> 24), out[4 * i + 1] = uint8_t(h[i] >> 16), out[4 * i + 2] = uint8_t(h[i] >> 8),
                out[4 * i + 3] = uint8_t(h[i]);
    }
}

// ---- CRC-32 ----

uint32_t crc32_update_hw(uint32_t crc, const void* data, size_t n) {
    // The CRC32 instructions keep the running CRC in the same "inverted"
    // form the software version uses internally (~crc in, ~crc out), so the
    // chaining is identical: start 0, each call's output feeds the next.
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint32_t c = ~crc;
    while (n && (reinterpret_cast<uintptr_t>(p) & 7)) {
        c = __crc32b(c, *p++);
        n--;
    }
    while (n >= 8) {
        uint64_t v;
        std::memcpy(&v, p, 8);
        c = __crc32d(c, v);
        p += 8, n -= 8;
    }
    while (n >= 4) {
        uint32_t v;
        std::memcpy(&v, p, 4);
        c = __crc32w(c, v);
        p += 4, n -= 4;
    }
    while (n--) c = __crc32b(c, *p++);
    return ~c;
}

}  // namespace nxi

#endif  // defined(__aarch64__)
