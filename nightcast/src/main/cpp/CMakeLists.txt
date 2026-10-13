cmake_minimum_required(VERSION 3.22.1)
project(nightcast C)

# OpenSSL comes from the prefab package declared in build.gradle.kts.
find_package(openssl REQUIRED CONFIG)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(UX ${TP}/uxplay/lib)
set(PL ${TP}/libplist)

# ---- libplist (binary plists are the AirPlay control-channel format) --------------------------
add_library(plist STATIC
    ${PL}/src/base64.c ${PL}/src/bplist.c ${PL}/src/common.c ${PL}/src/bytearray.c ${PL}/src/hashtable.c
    ${PL}/src/jplist.c ${PL}/src/jsmn.c ${PL}/src/oplist.c ${PL}/src/out-default.c
    ${PL}/src/out-limd.c ${PL}/src/out-plutil.c ${PL}/src/plist.c ${PL}/src/ptrarray.c
    ${PL}/src/time64.c ${PL}/src/xplist.c
    ${PL}/libcnary/node.c ${PL}/libcnary/node_list.c)
target_include_directories(plist
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/plist_config ${PL}/src ${PL}/libcnary/include
    PUBLIC ${PL}/include)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H PUBLIC LIBPLIST_STATIC)
target_compile_options(plist PRIVATE -O2 -w)

# ---- UxPlay's protocol library: RTSP server, pairing, FairPlay, RTP, built-in mDNS responder --
file(GLOB UX_SRCS ${UX}/*.c ${UX}/llhttp/*.c ${UX}/playfair/*.c ${UX}/mdnsd/*.c)
add_library(uxplay STATIC ${UX_SRCS})
target_include_directories(uxplay PUBLIC ${UX} PRIVATE ${UX}/llhttp ${UX}/playfair ${UX}/mdnsd)
target_compile_definitions(uxplay PRIVATE
    STANDALONE TARGET_POSIX _LINUX _REENTRANT NOHOLD PLIST_210 PLIST_230
    OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(uxplay PRIVATE -O2 -w)
target_link_libraries(uxplay PUBLIC plist openssl::crypto)

# ---- JNI bridge -------------------------------------------------------------------------------
add_library(nightcastjni SHARED jni_bridge.c ${TP}/alac/alac.c)
target_include_directories(nightcastjni PRIVATE ${TP}/alac)
target_compile_options(nightcastjni PRIVATE -O2 -Wall)
target_link_libraries(nightcastjni uxplay log)
