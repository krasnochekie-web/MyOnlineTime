#!/bin/bash

# Чистим старое
rm -rf libs
mkdir -p libs
cd libs

echo "🔥 Скачивание и лечение библиотек..."

# Функция: Качает, распаковывает, УДАЛЯЕТ module-info.class, переименовывает
get_lib() {
    NAME=$1
    URL=$2
    echo "⬇️ $NAME..."
    wget -q "$URL" -O temp.zip
    
    # Если это .aar - достаем classes.jar
    if [[ "$URL" == *".aar" ]]; then
        unzip -q -o temp.zip classes.jar
        mv classes.jar "$NAME.jar"
    else
        mv temp.zip "$NAME.jar"
    fi
    
    # ⚠️ ГЛАВНОЕ ЛЕЧЕНИЕ: Удаляем module-info.class из JAR
    zip -d "$NAME.jar" module-info.class > /dev/null 2>&1 || true
    
    # Удаляем мусор
    rm -f temp.zip
}

# --- FIREBASE (Версии подобраны) ---
get_lib "firebase-database" "https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-database/20.0.4/firebase-database-20.0.4.aar"
get_lib "firebase-common" "https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-common/20.1.0/firebase-common-20.1.0.aar"
get_lib "firebase-components" "https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-components/17.0.0/firebase-components-17.0.0.aar"
get_lib "firebase-database-collection" "https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-database-collection/18.0.1/firebase-database-collection-18.0.1.jar"
get_lib "firebase-auth-interop" "https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-auth-interop/19.0.2/firebase-auth-interop-19.0.2.aar"

# --- PLAY SERVICES ---
get_lib "play-services-auth" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-auth/20.4.1/play-services-auth-20.4.1.aar"
get_lib "play-services-auth-base" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-auth-base/18.0.4/play-services-auth-base-18.0.4.aar"
get_lib "play-services-base" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-base/18.2.0/play-services-base-18.2.0.aar"
get_lib "play-services-basement" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-basement/18.1.0/play-services-basement-18.1.0.aar"
get_lib "play-services-tasks" "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-tasks/18.0.2/play-services-tasks-18.0.2.aar"

# --- ANDROIDX & KOTLIN ---
get_lib "androidx-core" "https://dl.google.com/dl/android/maven2/androidx/core/core/1.6.0/core-1.6.0.aar"
get_lib "androidx-fragment" "https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/1.3.6/fragment-1.3.6.aar"
get_lib "androidx-activity" "https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.2.4/activity-1.2.4.aar"
get_lib "androidx-collection" "https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.1.0/collection-1.1.0.jar"
get_lib "androidx-versionedparcelable" "https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar"
get_lib "androidx-lifecycle-common" "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.3.1/lifecycle-common-2.3.1.jar"
get_lib "androidx-lifecycle-runtime" "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.3.1/lifecycle-runtime-2.3.1.aar"
get_lib "androidx-lifecycle-viewmodel" "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.3.1/lifecycle-viewmodel-2.3.1.aar"
get_lib "androidx-savedstate" "https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.1.0/savedstate-1.1.0.aar"
get_lib "kotlin-stdlib" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.7.10/kotlin-stdlib-1.7.10.jar"

echo "✅ Библиотеки готовы и очищены!"
cd ..

