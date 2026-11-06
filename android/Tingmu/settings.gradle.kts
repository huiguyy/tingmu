// 本机网络环境 dl.google.com / repo1.maven.org / maven.aliyun.com 均不可达（DNS 失败），
// 实测只有腾讯云镜像可达，故放在最前兜底；google() 保留供网络正常环境使用。
pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
    }
}
rootProject.name = "Tingmu"
include(":app")
