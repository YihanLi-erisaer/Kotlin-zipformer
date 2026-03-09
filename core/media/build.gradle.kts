plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.example.kotlin_asr_with_ncnn.core.media"
    compileSdk = 34

    defaultConfig {
        minSdk = 27

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-fexceptions", "-fopenmp")
                arguments.addAll(listOf(
                    "-DANDROID_CPP_FEATURES=exceptions",
                    "-DANDROID_STL=c++_shared",
                    "-DOpenMP_C_FLAGS=-fopenmp",
                    "-DOpenMP_CXX_FLAGS=-fopenmp",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-static-openmp"
                ))
                abiFilters.add("arm64-v8a")
            }
        }
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            java.srcDirs("src/main/kotlin")
            // Ensure prebuilt .so files are included in the APK
            jniLibs.srcDirs("src/main/cpp/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
