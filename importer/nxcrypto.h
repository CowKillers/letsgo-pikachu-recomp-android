// Self-contained AES-128 (ECB/CTR/XTS as Switch content uses them), SHA-256 and CRC-32 for the on-device importer.
// Portable C++ (no OpenSSL/mbedTLS): the same code runs in the PC test harness and in the APK.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

namespace nxi {

class Aes128 {
public:
    void set_key(const uint8_t key[16]);
    void encrypt_block(const uint8_t in[16], uint8_t out[16]) const;
    void decrypt_block(const uint8_t in[16], uint8_t out[16]) const;
    // The 11 round keys (176 bytes) in memory order, for the ARMv8 AES
    // instructions (nxcrypto_arm.cpp): the key schedule is stored as
    // little-endian words, so the bytes in memory are the round-key bytes.
    const uint8_t* round_keys() const { return reinterpret_cast<const uint8_t*>(rk_); }
    ~Aes128();  // wipes the round keys

private:
    uint32_t rk_[44] = {};
};

// ECB decrypt of n bytes (n a multiple of 16), in place.
void aes_ecb_decrypt(const Aes128& k, uint8_t* data, size_t n);
// CTR over n bytes in place; iv is the 16-byte counter block of data[0] (big-endian, incremented per block).
void aes_ctr(const Aes128& k, const uint8_t iv[16], uint8_t* data, size_t n);
// XTS with Nintendo's tweak (sector number big-endian), decrypting n bytes (a whole number of sectors) in place.
void aes_xts_decrypt(const Aes128& data_key, const Aes128& tweak_key, uint64_t first_sector, size_t sector_size,
                     uint8_t* data, size_t n);

struct Sha256 {
    Sha256();
    void update(const void* data, size_t n);
    void final(uint8_t out[32]);

private:
    void block(const uint8_t* p);
    uint32_t h_[8];
    uint64_t len_ = 0;
    uint8_t buf_[64];
    size_t used_ = 0;
};
void sha256(const void* data, size_t n, uint8_t out[32]);

uint32_t crc32_update(uint32_t crc, const void* data, size_t n);  // start with 0

// Zeroes key material in a way the optimiser keeps.
void secure_zero(void* p, size_t n);

bool crypto_self_test(const std::function<void(const char*)>& log = nullptr,
                      const char** failed = nullptr);
// Known-answer checks (FIPS-197 / SHA-256 / CRC). Each check logs its own
// result through `log` (empty = silent); on failure, `*failed` (if non-null)
// receives the name of the first failing check ("aes-fips197-enc",
// "aes-fips197-dec", "sha256-abc", "sha256-vec56", "sha256-million", "crc32",
// "crc32-chain", "aes-ctr", "aes-ctr-tail"). The SHA-256 / CRC-32 / AES-CTR
// checks go through the routed functions, so on a device with the extensions
// they exercise the hardware path; the FIPS-197 encrypt/decrypt check uses
// the software implementation directly. Default arguments keep the old
// call sites (no logging) compiling.

// After a hardware self-test failure: stop using the hardware paths for the
// rest of the process (the software implementations are the proven ones).
// No-op on the host (no hardware paths there).
void crypto_disable_hw();

#if defined(__aarch64__)
// ARMv8 Cryptography Extensions (nxcrypto_arm.cpp, compiled with
// -march=armv8-a+crypto+crc). Each function is called only when the CPU has
// the feature; see crypto_hw_features() in nxcrypto.cpp. The host (x86_64)
// never compiles that file, so these do not exist there.
enum : unsigned { kHwAes = 1u, kHwSha2 = 2u, kHwCrc32 = 4u };
unsigned crypto_hw_features();  // cached HWCAP probe (0 when the feature is absent)
void aes_ctr_hw(const Aes128& k, const uint8_t iv[16], uint8_t* data, size_t n);
void sha256_hw(const void* data, size_t n, uint8_t out[32]);
uint32_t crc32_update_hw(uint32_t crc, const void* data, size_t n);
#endif

}  // namespace nxi
