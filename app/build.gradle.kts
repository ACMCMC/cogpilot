plugins {
    alias(libs.plugins.android.application)
}

// Read environment variables for credentials
fun loadDotEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) return emptyMap()

    return envFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf('=')
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"').trim('\'')
            key to value
        }
}

val dotEnv = loadDotEnv()

fun getEnvOrProperty(key: String, default: String = ""): String {
    return dotEnv[key]
        ?: System.getenv(key)
        ?: project.findProperty(key)?.toString()
        ?: default
}

android {
    namespace = "fyi.acmc.cogpilot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "fyi.acmc.cogpilot"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build config fields from environment variables
        buildConfigField("String", "SNOWFLAKE_ACCOUNT", "\"${getEnvOrProperty("SNOWFLAKE_ACCOUNT")}\"")
        buildConfigField("String", "SNOWFLAKE_PAT_TOKEN", "\"${getEnvOrProperty("SNOWFLAKE_PAT_TOKEN")}\"")
        buildConfigField("String", "SNOWFLAKE_WAREHOUSE", "\"${getEnvOrProperty("SNOWFLAKE_WAREHOUSE", "COMPUTE_WH")}\"")
        buildConfigField("String", "SNOWFLAKE_DATABASE", "\"${getEnvOrProperty("SNOWFLAKE_DATABASE", "COGPILOT_DB")}\"")
        buildConfigField("String", "SNOWFLAKE_SCHEMA", "\"${getEnvOrProperty("SNOWFLAKE_SCHEMA", "PUBLIC")}\"")
        buildConfigField("String", "SNOWFLAKE_ROLE", "\"${getEnvOrProperty("SNOWFLAKE_ROLE", "ACCOUNTADMIN")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${getEnvOrProperty("ELEVENLABS_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_AGENT_ID", "\"${getEnvOrProperty("ELEVENLABS_AGENT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.material3)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}