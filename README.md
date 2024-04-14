# Disable FLAG_SECURE
Disable `FLAG_SECURE` on all windows, enabling screenshots in apps that normally wouldn\'t allow it, and disabling screenshot detection on Android 14+.

## init env

```shell
mkdir libxposed

git clone https://github.com/libxposed/api.git
cd api
echo 'org.gradle.caching=true' >> gradle.properties
echo 'org.gradle.parallel=true' >> gradle.properties
echo 'org.gradle.vfs.watch=true' >> gradle.properties
echo 'org.gradle.jvmargs=-Xmx2048m' >> gradle.properties
./gradlew publishToMavenLocal
cd ..

git clone https://github.com/libxposed/service.git
cd service
# 报错 多运行几次 或者删除缓存文件后继续  ~/.gradle/caches
./gradlew publishToMavenLocal
cd ..
```

## Usage
1. Enable the module
2. Select **ONLY** recommended apps
3. Reboot


