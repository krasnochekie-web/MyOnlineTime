#!/bin/bash
# Этот скрипт скачивает и готовит рабочие инструменты для сборки.
# Запустите его один раз.

set -e

echo "--- Шаг 1: Установка wget и unzip ---"
pkg install wget unzip -y

echo "--- Шаг 2: Очистка старых попыток ---"
rm -f commandlinetools-linux-*.zip
rm -rf cmdline-tools

echo "--- Шаг 3: Скачивание официальных инструментов Android SDK ---"
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip

if [ ! -f "commandlinetools-linux-9477386_latest.zip" ]; then
    echo "❌ ОШИБКА: Не удалось скачать инструменты. Проверьте интернет-соединение."
    exit 1
fi

echo "--- Шаг 4: Распаковка архива ---"
unzip -q commandlinetools-linux-9477386_latest.zip

if [ ! -d "cmdline-tools" ]; then
    echo "❌ ОШИБКА: Архив распаковался, но папка 'cmdline-tools' не появилась."
    exit 1
fi

echo "--- Шаг 5: Проверка результата ---"
if [ -f "cmdline-tools/lib/d8.jar" ]; then
    echo "✅ УСПЕХ! Рабочий компилятор d8.jar успешно подготовлен."
    echo "Теперь вы можете запустить основной скрипт сборки: ./build.sh"
else
    echo "❌ КРИТИЧЕСКАЯ ОШИБКА: d8.jar не найден внутри cmdline-tools/lib/."
    echo "Пожалуйста, покажите мне вывод этой команды:"
    ls -lR cmdline-tools
fi
	
