plugins {
    id("com.android.application")
    id("com.figma.sync")
}

android {
    namespace = "com.example.figmademo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.figmademo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// ═══════════════════════════════════════════════════════════════════
//  Figma Sync Plugin Configuration
//  Token is auto-read from local.properties (figma.token=xxx)
//  Icons are downloaded from 车辆控制 SECTION, skipping 模版 nodes.
//  RTL variants → drawable-ldrtl/, LTR → drawable/
// ═══════════════════════════════════════════════════════════════════
//
//  declarations: 声明本 App 需要使用的图标名称（COMPONENT_SET 名）
//   - 声明后只会下载列表中匹配的图标，未声明的自动跳过
//   - 同名多 variant（如 icon_car_door 的多个变体）自动全部包含
//   - RTL 变体自动跟随 LTR 声明
//   - 列表为空 = 下载全部图标
// ═══════════════════════════════════════════════════════════════════

figmaSync {
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"
    // pinnedVersion = "2361395147552737305"  // 0604 test V91: 取消注释即锁定版本，跳过 API 调用
    tokens {
        enabled = true
        output = "figma_colors.xml"
        chainDownload = true
    }
    icons {
        enabled = true
        startNode = "14832:59978"  // 车辆控制
        scale = 2
        extractTokens = true

        // ── 图标声明列表 ─────────────────────────────────────────
        // 只下载以下声明的图标（按 Figma COMPONENT_SET 名称匹配）
        // 删掉不需要的行即可减少下载量
        declarations = listOf(
            // 车门
            "icon_car_door",
            "icon_left_door",
            "icon_right_door",
            "icon_left_door_open",
            "icon_right_door_open",
            "icon_window",
            "icon_close_all",
            "icon_fully_open",
            "icon_child_lock",
            "icon_unlock",

            // 灯光
            "icon_low_beam",
            "icon_high_beam",
            "icon_marker_light",
            "icon_curvature_beam",
            "icon_low_beams",
            "icon_reading_light_on",
            "icon_reading_light_off",
            "icon_parking_lights",
            "icon_rear_fog_lights",
            "icon_atmosphere_lamp",

            // 后视镜
            "icon_exterior_mirror",
            "icon_interior_rearview_mirror",
            "icon_folding_exterior_mirrors",
            "icon_heated_exterior_mirrors",

            // 前盖 / 后盖
            "icon_front_cover",
            "icon_trunk",
            "icon_trunk_closed",
            "icon_trunk_open",

            // 天窗 / 遮阳帘
            "icon_sunshade",

            // 充电 / 能源
            "icon_ac_charging",
            "icon_dc_charging",
            "icon_charging_gun",
            "icon_charging_pile",
            "icon_charger_port",
            "icon_battery_life",
            "icon_fuel",
            "icon_fuel_cap",
            "icon_electricity",
            "icon_during_discharge",
            "icon_energy_management",
            "icon_energy_model",
            "icon_quick_power",
            "icon_ordinary_power",
            "icon_ultimate_energy_efficiency",

            // 驾驶
            "icon_driving_comfort",
            "icon_driving_mode_eco",
            "icon_driving_mode_custom",
            "icon_driving_mode_sport",
            "icon_steering_wheel",
            "icon_suspension_leveling",
            "icon_tow_mode",
            "icon_towhook",
            "icon_easily_overcome",
            "icon_epb",

            // 保养 / 清洁
            "icon_car_wash_mode",
            "icon_change_tires",
            "icon_wiper_maintenance",
            "icon_screen_cleaning",
            "icon_camera_cleaning",
            "icon_initialization",
            "icon_tire_pressure1",

            // 连接 / 显示
            "icon_bluetooth_music",
            "icon_bluetooth_phone",
            "icon_mobile_interconnection",
            "icon_phone",
            "icon_connect",
            "icon_display",
            "icon_brightness",

            // 快捷控制
            "icon_quick_nor",
            "icon_ads_nor",
            "icon_security",
            "icon_leave_the_car_no_power",
            "icon_wired"
        )
    }
}

// ── Hook: sync icons before every build ────────────────────────────
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("syncFigmaIcons")
}
