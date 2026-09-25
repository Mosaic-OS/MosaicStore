/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <fcntl.h>
#include <linux/fsverity.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <cstdint>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_mosaicos_models_ModelVerity_measure(JNIEnv *env, jclass, jint fd) {
    struct stat info{};
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY ||
            fstat(fd, &info) != 0 || !S_ISREG(info.st_mode)) return nullptr;
    struct {
        uint16_t algorithm;
        uint16_t size;
        uint8_t digest[32];
    } measured{};
    measured.size = sizeof(measured.digest);
    if (ioctl(fd, FS_IOC_MEASURE_VERITY, &measured) != 0 ||
            measured.algorithm != FS_VERITY_HASH_ALG_SHA256 || measured.size != 32) return nullptr;
    jbyteArray result = env->NewByteArray(32);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte *>(measured.digest));
    }
    return result;
}
