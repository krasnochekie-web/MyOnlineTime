#!/bin/bash

# Создаем папку libs
mkdir -p libs
cd libs

echo "🚀 Начинаем скачивание библиотек..."

# --- ФУНКЦИЯ 1: Для AAR файлов (распаковываем и достаем classes.jar) ---
download_aar() {
    NAME=$1
    URL=$2
    echo "⬇️ Скачиваю $NAME..."
    wget -q --show-progress "$URL" -O "$NAME.aar"
    echo "📦 Распаковка..."
    unzip -q -o "$NAME.aar" classes.jar
    mv -f classes.jar "$NAME.jar"
    rm -f "$NAME.aar"
    rm -rf jni res AndroidManifest.xml R.txt proguard.txt public.txt META-INF aidl assets
    echo "✅ $NAME готов!"
}

# --- ФУНКЦИЯ 2: Для JAR файлов (просто скачиваем) ---
download_jar() {
    NAME=$1
    URL=$2
    echo "⬇️ Скачиваю $NAME..."
    wget -q --show-progress "$URL" -O "$NAME.jar"
    echo "✅ $NAME готов!"
}

# --- СКАЧИВАНИЕ GOOGLE PLAY SERVICES ---
download_aar "play-services-auth" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-auth/20.7.0/play-services-auth-20.7.0.aar"
download_aar "play-services-auth-base" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-auth-base/18.0.4/play-services-auth-base-18.0.4.aar"
download_aar "play-services-base" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-base/18.2.0/play-services-base-18.2.0.aar"
download_aar "play-services-basement" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-basement/18.2.0/play-services-basement-18.2.0.aar"
download_aar "play-services-tasks" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-tasks/18.0.2/play-services-tasks-18.0.2.aar"

# --- СКАЧИВАНИЕ ANDROIDX ---
download_aar "androidx-core" "https://dl.google.com/dl/android/maven2/androidx/core/core/1.6.0/core-1.6.0.aar"
download_jar "androidx-collection" "https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.1.0/collection-1.1.0.jar"
download_aar "androidx-fragment" "https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/1.3.6/fragment-1.3.6.aar"
download_aar "androidx-activity" "https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.2.4/activity-1.2.4.aar"
download_aar "androidx-loader" "https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0.aar"
download_aar "androidx-viewpager" "https://dl.google.com/dl/android/maven2/androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.aar"
download_aar "androidx-customview" "https://dl.google.com/dl/android/maven2/androidx/customview/customview/1.0.0/customview-1.0.0.aar"
download_aar "androidx-lifecycle-viewmodel" "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.3.1/lifecycle-viewmodel-2.3.1.aar"
download_jar "androidx-lifecycle-common" "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.3.1/lifecycle-common-2.3.1.jar"
download_jar "androidx-annotation" "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar"
download_aar "androidx-savedstate" "https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.1.0/savedstate-1.1.0.aar"

echo "🎉 Готово! Все библиотеки в папке libs."
cd ..

