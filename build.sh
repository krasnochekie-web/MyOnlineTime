#!/bin/bash
# Скрипт сборки MyNewTime.apk v17.2 (Fix Permissions in Loop)

set -e

# --- 1. НАСТРОЙКИ ---
ANDROID_JAR="$PWD/android.jar" 
KEYSTORE="mykey.keystore"
PACKAGE_NAME="com/myonlinetime/app"

echo "🧹 Очистка..."
chmod -R 777 build 2>/dev/null || true
rm -rf build MyNewTime.apk
mkdir -p build/gen build/obj build/apk build/dex_parts build/tmp_lib

# --- 2. СБОР CLASSPATH ---
echo "📚 Сбор библиотек..."
CLASSPATH="$ANDROID_JAR"
if ls libs/*.jar &> /dev/null; then
    for jar in libs/*.jar; do
        CLASSPATH="$CLASSPATH:$jar"
    done
fi

# --- 3. РЕСУРСЫ ---
echo "🖼️ Компиляция ресурсов..."
aapt2 compile --dir app/src/main/res -o build/resources.zip
aapt2 link -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    -o build/apk/resources.apk \
    --java build/gen \
    --auto-add-overlay build/resources.zip

# --- 4. КОМПИЛЯЦИЯ JAVA ---
echo "☕ Компиляция Java кода..."
find app/src/main/java -name "*.java" > build/sources.txt
find build/gen -name "*.java" >> build/sources.txt

javac -d build/obj \
    -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$CLASSPATH" \
    @build/sources.txt

# --- 5. DEX-ИФИКАЦИЯ ---
echo "⚙️ Генерация DEX (очистка и компиляция)..."

dex_it() {
    local jar_in="$1"
    local dex_out="$2"
    local name=$(basename "$jar_in")
    
    echo "   -> Обработка $name..."
    
    # 1. Распаковка
    rm -rf build/tmp_lib/*
    unzip -qo "$jar_in" -d build/tmp_lib
    
    # 2. ИСПРАВЛЕНИЕ ПРАВ (Самое важное!)
    chmod -R 777 build/tmp_lib
    
    # 3. Санирование
    rm -rf build/tmp_lib/META-INF/versions
    
    # 4. ПРОВЕРКА: Есть ли внутри .class файлы?
    if [ -n "$(find build/tmp_lib -name "*.class" | head -n 1)" ]; then
        # Файлы есть, пакуем и компилируем
        jar cvf build/clean_temp.jar -C build/tmp_lib . > /dev/null
        dx --dex --min-sdk-version=23 --output="$dex_out" build/clean_temp.jar
    else
        echo "      ⚠️ Пустой JAR или нет классов. Пропуск."
    fi
}

# 5.1. Наш код
jar cvf build/my_app.jar -C build/obj .
dex_it "build/my_app.jar" "build/dex_parts/classes_app.dex"

# 5.2. JAR библиотеки
count=1
if ls libs/*.jar &> /dev/null; then
    for jar in libs/*.jar; do
        dex_it "$jar" "build/dex_parts/classes_lib_${count}.dex"
        count=$((count+1))
    done
fi

# 5.3. AAR библиотеки
if ls libs/*.aar &> /dev/null; then
    for aar in libs/*.aar; do
        echo "   -> Извлечение AAR $(basename "$aar")..."
        unzip -p "$aar" classes.jar > build/temp_aar.jar 2>/dev/null || true
        if [ -s build/temp_aar.jar ]; then
             dex_it "build/temp_aar.jar" "build/dex_parts/classes_aar_${count}.dex"
             count=$((count+1))
        fi
        rm -f build/temp_aar.jar
    done
fi

# --- 6. УПАКОВКА В APK ---
echo "📦 Сборка финального APK..."
cp build/apk/resources.apk build/apk/MyNewTime.unsigned.apk

cd build/dex_parts
# Главный classes.dex - это наш код
if [ -f "classes_app.dex" ]; then
    mv classes_app.dex ../apk/classes.dex
fi

# Остальные файлы именуем classes2.dex, classes3.dex ...
i=2
for f in *.dex; do
    if [ "$f" != "classes_app.dex" ] && [ -f "$f" ]; then
        mv "$f" "../apk/classes${i}.dex"
        i=$((i+1))
    fi
done
cd ../..

# Добавляем все DEX файлы в архив
cd build/apk
zip -u MyNewTime.unsigned.apk classes*.dex
cd ../..

echo -n "Введите пароль от $KEYSTORE: "
read -s KEYPASS_VAR
echo

apksigner sign --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYPASS_VAR" \
    --out MyNewTime.apk \
    build/apk/MyNewTime.unsigned.apk

echo "✅ ГОТОВО! Файл: MyNewTime.apk"

