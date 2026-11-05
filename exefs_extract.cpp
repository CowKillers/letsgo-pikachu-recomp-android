// ExeFS extractor bypassing hactool: uses the project's own importer crypto
// (importer/nxcrypto.cpp + the key derivation in importer/nximport.cpp),
// which was already proven to decrypt every NCA header of both games.
// Prints ONLY file names, sizes and SHA-256 hashes — never key values.
#include "../importer/nxcrypto.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>
#include <map>
#include <vector>
#include <fstream>
#include <iostream>
#include <filesystem>
namespace fs = std::filesystem;
using namespace nxi;
static int hexval(char c){ if(c>='0'&&c<='9') return c-'0'; c=tolower((unsigned char)c); if(c>='a'&&c<='f') return c-'a'+10; return -1; }
static bool parse_hex(const std::string& s, std::vector<uint8_t>& out){
  if(s.size()%2) return false; out.resize(s.size()/2);
  for(size_t i=0;i<out.size();i++){ int a=hexval(s[2*i]), b=hexval(s[2*i+1]); if(a<0||b<0) return false; out[i]=(uint8_t)(a<<4|b); }
  return true;
}
static std::string trim(std::string s){ size_t a=s.find_first_not_of(" \t\r\n"), b=s.find_last_not_of(" \t\r\n"); return a==std::string::npos?std::string():s.substr(a,b-a+1); }
static std::string lower(std::string s){ for(char& c: s) c=tolower((unsigned char)c); return s; }
static uint32_t rd32(const uint8_t* p){ return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }
static uint64_t rd64(const uint8_t* p){ return (uint64_t)rd32(p)|((uint64_t)rd32(p+4)<<32); }
static void wr_be64(uint8_t* p, uint64_t v){ for(int i=0;i<8;i++) p[i]=(uint8_t)(v>>(56-8*i)); }
static void unwrap(const uint8_t k[16], const uint8_t src[16], uint8_t out[16]){ Aes128 a; a.set_key(k); a.decrypt_block(src,out); }
struct Keys{
  std::map<std::string, std::vector<uint8_t>> named;
  bool get(const std::string& n, size_t len, uint8_t* out) const { auto it=named.find(n); if(it==named.end()||it->second.size()!=len) return false; memcpy(out,it->second.data(),len); return true; }
  static std::string idx(const char* base, int rev){ char b[64]; snprintf(b,sizeof b,"%s_%02x",base,rev); return b; }
  bool derive(const std::string& name, uint8_t out[16]) const {
    if(get(name,16,out)) return true;
    int rev=-1; auto tail=name.rfind('_'); if(tail!=std::string::npos) rev=(int)strtol(name.c_str()+tail+1,nullptr,16);
    uint8_t mk[16], src[16]; if(rev<0||!get(idx("master_key",rev),16,mk)) return false;
    bool ok=false;
    if(name.rfind("titlekek_",0)==0 && get("titlekek_source",16,src)){ unwrap(mk,src,out); ok=true; }
    else if(name.rfind("key_area_key_",0)==0){
      std::string which=name.substr(13, tail-13); uint8_t kek_src[16], key_src[16], kek[16], srckek[16];
      if(get("key_area_key_"+which+"_source",16,src) && get("aes_kek_generation_source",16,kek_src) && get("aes_key_generation_source",16,key_src)){
        unwrap(mk,kek_src,kek); unwrap(kek,src,srckek); unwrap(srckek,key_src,out); ok=true;
      }
    }
    return ok;
  }
  bool header_key(uint8_t out[32]) const {
    if(get("header_key",32,out)) return true;
    uint8_t mk[16], kek_src[16], key_src[16], hkek_src[16], kek[16], srckek[16], hkek[16], hsrc[32];
    bool ok=get("master_key_00",16,mk)&&get("aes_kek_generation_source",16,kek_src)&&get("aes_key_generation_source",16,key_src)&&get("header_kek_source",16,hkek_src)&&get("header_key_source",32,hsrc);
    if(ok){ unwrap(mk,kek_src,kek); unwrap(kek,hkek_src,srckek); unwrap(srckek,key_src,hkek); unwrap(hkek,hsrc,out); unwrap(hkek,hsrc+16,out+16); }
    return ok;
  }
};
static std::string hex(const uint8_t* p, size_t n){ static const char* h="0123456789abcdef"; std::string s; for(size_t i=0;i<n;i++){ s+=h[p[i]>>4]; s+=h[p[i]&15]; } return s; }
// Read raw bytes from NSP file at absolute offset.
static bool read_at(std::ifstream& f, uint64_t off, uint8_t* dst, size_t n){
  f.seekg((std::streamoff)off); if(!f) return false; f.read((char*)dst,n); return (size_t)f.gcount()==n;
}
// CTR-decrypt a section: raw bytes at nsp_off, counter based on nca_off (section offset inside the NCA).
static void ctr_section(std::ifstream& f, uint64_t nsp_off, uint64_t nca_off, const Aes128& key, const uint8_t upper[8], uint8_t* dst, size_t n){
  // Replicates importer ctr_read: 4 MiB chunks, IV = upper : BE64((ctr_off-skip)>>4).
  uint64_t ctr_off=nca_off; size_t done=0;
  while(done<n){
    size_t want=std::min<size_t>(n-done, 4u<<20);
    uint64_t skip=ctr_off&15; size_t span=(size_t)((skip+want+15)&~uint64_t(15));
    std::vector<uint8_t> buf(span);
    read_at(f, nsp_off+done, buf.data(), std::min<size_t>(span, n-done+skip));
    uint8_t iv[16]; memcpy(iv,upper,8); wr_be64(iv+8,(ctr_off-skip)>>4);
    aes_ctr(key, iv, buf.data(), span);
    memcpy(dst+done, buf.data()+skip, want);
    done+=want; ctr_off+=want;
  }
}
int main(int argc, char** argv){
  if(argc<4){ printf("usage: exefs_extract <prod.keys> <nsp> <outdir>\n"); return 2; }
  if(!crypto_self_test()){ printf("crypto self-test FAILED\n"); return 2; }
  Keys keys; { std::ifstream in(argv[1]); std::string line; while(std::getline(in,line)){ auto eq=line.find('='); if(eq==std::string::npos) continue; std::vector<uint8_t> b; if(parse_hex(trim(line.substr(eq+1)), b) && !b.empty()) keys.named[lower(trim(line.substr(0,eq)))]=b; } }
  uint8_t hk[32]; if(!keys.header_key(hk)){ printf("no header_key\n"); return 2; }
  std::ifstream f(argv[2], std::ios::binary); if(!f){ printf("cannot open NSP\n"); return 2; }
  uint8_t ph[16]; if(!read_at(f,0,ph,16)||memcmp(ph,"PFS0",4)!=0){ printf("not PFS0\n"); return 2; }
  uint32_t count=rd32(ph+4), strsz=rd32(ph+8); uint64_t data0=16+(uint64_t)count*24+strsz;
  struct Ent{ uint64_t off,size; uint32_t so; std::string name; };
  std::vector<Ent> ents; std::vector<char> strs(strsz);
  for(uint32_t i=0;i<count;i++){ uint8_t e[24]; read_at(f,16+(uint64_t)i*24,e,24); Ent en; en.off=rd64(e); en.size=rd64(e+8); en.so=rd32(e+16); ents.push_back(en); }
  read_at(f,16+(uint64_t)count*24,(uint8_t*)strs.data(),strsz);
  for(auto& en: ents){ en.name=std::string(strs.data()+en.so, strnlen(strs.data()+en.so, strsz-en.so)); }
  // Find program NCA: content_type 0, largest.
  struct Prog{ Ent ent; std::vector<uint8_t> hdr; uint8_t content_key[16]; int rev; };
  Prog* prog=nullptr; std::vector<Prog> progs;
  for(auto& en: ents){
    if(en.name.size()<=4||en.name.substr(en.name.size()-4)!=".nca") continue;
    std::vector<uint8_t> hdr(0xC00); if(!read_at(f,data0+en.off,hdr.data(),0xC00)) continue;
    Aes128 k1,k2; k1.set_key(hk); k2.set_key(hk+16); aes_xts_decrypt(k1,k2,0,0x200,hdr.data(),0xC00);
    if(memcmp(hdr.data()+0x200,"NCA3",4)!=0) continue;
    const uint8_t* h=hdr.data()+0x200;
    if(h[5]!=0) continue; // program only
    int gen=std::max<int>(h[6],h[0x20]); int rev=gen?gen-1:0;
    static const char* kinds[3]={"key_area_key_application","key_area_key_ocean","key_area_key_system"};
    int which=h[7]<3?h[7]:0; uint8_t kak[16];
    if(!keys.derive(Keys::idx(kinds[which],rev),kak)){ printf("no key_area_key for rev %d\n",rev); return 2; }
    Prog p; p.ent=en; p.hdr=hdr; p.rev=rev; unwrap(kak,h+0x100+0x20,p.content_key); progs.push_back(std::move(p));
  }
  if(progs.empty()){ printf("no program NCA found\n"); return 2; }
  prog=&progs[0]; for(auto& p: progs) if(p.ent.size>prog->ent.size) prog=&p;
  printf("program NCA: %s size=%llu rev=%d\n", prog->ent.name.c_str(), (unsigned long long)prog->ent.size, prog->rev);
  const uint8_t* h=prog->hdr.data()+0x200;
  // Sections
  int exe_sec=-1; uint64_t exe_off=0, exe_size=0; const uint8_t* exe_fs=nullptr; uint8_t upper[8]={}; uint8_t crypt=0;
  for(int i=0;i<4;i++){
    uint32_t start=rd32(h+0x40+16*i), end=rd32(h+0x44+16*i); if(!start&&!end) continue;
    const uint8_t* fs=prog->hdr.data()+0x400+0x200*i;
    uint8_t digest[32]; sha256(fs,0x200,digest);
    if(memcmp(digest,h+0x80+0x20*i,32)!=0){ printf("fs header %d hash mismatch\n",i); return 2; }
    printf("section %d partition=%u crypt=%u off=%llu size=%llu\n", i, fs[2], fs[4], (unsigned long long)((uint64_t)start*0x200), (unsigned long long)((uint64_t)(end-start)*0x200));
    if(fs[2]==1 && exe_sec<0){ exe_sec=i; exe_off=(uint64_t)start*0x200; exe_size=(uint64_t)(end-start)*0x200; exe_fs=fs; crypt=fs[4]; for(int j=0;j<8;j++) upper[j]=fs[0x147-j]; }
  }
  if(exe_sec<0){ printf("no ExeFS section\n"); return 2; }
  printf("ExeFS section %d off=%llu size=%llu crypt=%u\n", exe_sec, (unsigned long long)exe_off, (unsigned long long)exe_size, crypt);
  Aes128 ckey; ckey.set_key(prog->content_key);
  std::vector<uint8_t> sec(exe_size);
  uint64_t nca_abs=data0+prog->ent.off;
  if(crypt==1){ if(!read_at(f,nca_abs+exe_off,sec.data(),exe_size)){ printf("read ExeFS failed\n"); return 2; } }
  else if(crypt==3||crypt==4){ ctr_section(f, nca_abs+exe_off, exe_off, ckey, upper, sec.data(), exe_size); }
  else { printf("unsupported ExeFS crypt %u\n",crypt); return 2; }
  // ExeFS layout from fs header
  uint32_t pfs_bs=rd32(exe_fs+0x28); uint64_t ht_off=rd64(exe_fs+0x30), ht_size=rd64(exe_fs+0x38), pfs_off=rd64(exe_fs+0x40), pfs_size=rd64(exe_fs+0x48);
  if(!pfs_bs||ht_size>(64u<<20)||pfs_size>(1ull<<31)||ht_off+ht_size>exe_size||pfs_off+pfs_size>exe_size){ printf("implausible ExeFS layout\n"); return 2; }
  uint8_t d[32]; sha256(sec.data()+ht_off,ht_size,d);
  if(memcmp(d,exe_fs+0x8,32)!=0){ printf("ExeFS hash table check failed\n"); return 2; }
  for(uint64_t b=0;b*pfs_bs<pfs_size;b++){ size_t m=(size_t)std::min<uint64_t>(pfs_bs,pfs_size-b*pfs_bs); sha256(sec.data()+pfs_off+b*pfs_bs,m,d); if((b+1)*32>ht_size||memcmp(d,sec.data()+ht_off+b*32,32)!=0){ printf("ExeFS block %llu fails its check\n",(unsigned long long)b); return 2; } }
  const uint8_t* pfs=sec.data()+pfs_off;
  if(memcmp(pfs,"PFS0",4)!=0){ printf("ExeFS is not a PFS0\n"); return 2; }
  uint32_t ecnt=rd32(pfs+4), estr=rd32(pfs+8); uint64_t edata0=16+(uint64_t)ecnt*24+estr;
  if(edata0>pfs_size){ printf("ExeFS header truncated\n"); return 2; }
  fs::create_directories(argv[3]);
  for(uint32_t i=0;i<ecnt;i++){
    const uint8_t* e=pfs+16+i*24; uint64_t off=edata0+rd64(e), size=rd64(e+8); uint32_t so=rd32(e+16);
    if(off+size>pfs_size||so>=estr){ printf("ExeFS entry %u out of range\n",i); return 2; }
    std::string name((const char*)pfs+16+ecnt*24+so, strnlen((const char*)pfs+16+ecnt*24+so, estr-so));
    if(name.empty()||name.find('/')!=std::string::npos||name[0]=='.'){ printf("unsafe ExeFS name\n"); return 2; }
    std::string out=std::string(argv[3])+"/"+name;
    std::ofstream o(out, std::ios::binary); o.write((const char*)pfs+off,size); o.close();
    uint8_t dg[32]; sha256(pfs+off,size,dg);
    printf("exefs: %s %llu sha256=%s\n", name.c_str(), (unsigned long long)size, hex(dg,32).c_str());
  }
  return 0;
}
