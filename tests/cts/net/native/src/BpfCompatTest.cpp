/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless requied by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#define LOG_TAG "BpfCompatTest"

#include <fstream>

#include <gtest/gtest.h>

#include "android-modules-utils/sdk_level.h"

#include "libbpf_android.h"

using namespace android::bpf;

void doBpfStructSizeTest(const char *elfPath, unsigned mapSz, unsigned progSz) {
  std::ifstream elfFile(elfPath, std::ios::in | std::ios::binary);
  ASSERT_TRUE(elfFile.is_open());

  EXPECT_EQ(mapSz, readSectionUint("size_of_bpf_map_def", elfFile, 0));
  EXPECT_EQ(progSz, readSectionUint("size_of_bpf_prog_def", elfFile, 0));
}

TEST(BpfTest, bpfStructSizeTest) {
  if (android::modules::sdklevel::IsAtLeastV()) {
    // Due to V+ using mainline netbpfload, there is no longer a need to
    // enforce consistency between platform and mainline bpf .o files.
    GTEST_SKIP() << "V+ device.";
  } else if (android::modules::sdklevel::IsAtLeastU()) {
    doBpfStructSizeTest("/system/etc/bpf/gpuMem.o", 120, 92);
    doBpfStructSizeTest("/system/etc/bpf/timeInState.o", 120, 92);
  } else if (android::modules::sdklevel::IsAtLeastT()) {
    doBpfStructSizeTest("/system/etc/bpf/gpu_mem.o", 116, 92);
    doBpfStructSizeTest("/system/etc/bpf/time_in_state.o", 116, 92);
  } else if (android::modules::sdklevel::IsAtLeastS()) {
    // These files were moved to mainline in Android T
    doBpfStructSizeTest("/system/etc/bpf/netd.o", 48, 28);
    doBpfStructSizeTest("/system/etc/bpf/clatd.o", 48, 28);
  } else {
    // There is no mainline bpf code before S.
    GTEST_SKIP() << "R- device.";
  }
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
