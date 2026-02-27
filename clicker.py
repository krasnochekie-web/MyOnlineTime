import os
import time
from PIL import Image

# Настройки цвета синей кнопки
TARGET_COLOR = (0, 93, 215)
THRESHOLD = 25
# Имя пакета Samsung Internet Beta
BROWSER_PACKAGE = "com.sec.android.app.sbrowser.beta"

def is_browser_active():
    """Проверяет, открыт ли именно Samsung Beta прямо сейчас"""
    try:
        # Получаем данные о фокусе окна
        cmd = "adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
        result = os.popen(cmd).read().lower()
        # Если в строке есть имя пакета или ключевое слово браузера Samsung
        return BROWSER_PACKAGE in result or "sbrowser" in result
    except:
        return False

def wait_and_hide_keyboard(timeout=8):
    """Следит за появлением клавиатуры после клика до 8 секунд"""
    print(f"[~] Ожидание клавиатуры (до {timeout} сек)...")
    start_time = time.time()
    
    while time.time() - start_time < timeout:
        # Если пользователь вдруг вышел из браузера во время ожидания - прерываемся
        if not is_browser_active():
            return

        os.system("adb shell screencap -p /data/local/tmp/kb.png")
        os.system("adb pull /data/local/tmp/kb.png ./kb.png > /dev/null 2>&1")
        
        if os.path.exists("kb.png"):
            try:
                img = Image.open("kb.png").convert("RGB")
                w, h = img.size
                # Проверяем пиксель в зоне клавиатуры (нижняя часть)
                r, g, b = img.getpixel((w // 2, h - 100))
                os.remove("kb.png")
                
                # Если цвет серый (R, G, B близки), значит клавиатура вылезла
                if abs(r - g) < 12 and abs(g - b) < 12 and r > 40:
                    print("[+] Клавиатура обнаружена. Скрываю!")
                    os.system("adb shell input keyevent 4")
                    return 
            except:
                pass
        time.sleep(1) 
    print("[-] Клавиатура не появилась, продолжаю поиск кнопки.")

def find_and_click():
    """Ищет синюю кнопку только в левой половине активного браузера"""
    if not is_browser_active():
        return "wait"

    os.system("adb shell screencap -p /data/local/tmp/screen.png")
    os.system("adb pull /data/local/tmp/screen.png ./screen.png > /dev/null 2>&1")
    
    if not os.path.exists("screen.png"):
        return False

    try:
        img = Image.open("screen.png").convert("RGB")
        w, h = img.size
        
        # Ограничиваем область: левая половина экрана
        search_width = w // 2
        for y in range(int(h * 0.2), int(h * 0.9), 30):
            for x in range(0, search_width, 30):
                r, g, b = img.getpixel((x, y))
                # Строгая проверка синего цвета
                if abs(r - TARGET_COLOR[0]) < THRESHOLD and \
                   abs(g - TARGET_COLOR[1]) < THRESHOLD and \
                   abs(b - TARGET_COLOR[2]) < THRESHOLD and \
                   b > (r + 45):
                    
                    print(f"[+] Кнопка найдена в {x}, {y}. Кликаю!")
                    os.system(f"adb shell input tap {x} {y}")
                    return True 
        return False
    finally:
        if os.path.exists("screen.png"):
            os.remove("screen.png")

print("=== ФИНАЛЬНЫЙ КЛИКЕР: Samsung Beta Only ===")
while True:
    status = find_and_click()
    
    if status == True:
        # Если нажали кнопку - запускаем режим ожидания клавиатуры
        wait_and_hide_keyboard(8)
        time.sleep(4) # Пауза перед следующим поиском
    elif status == "wait":
        # Если мы не в браузере - ничего не делаем
        time.sleep(2)
    else:
        # Если кнопки просто нет на экране
        time.sleep(1.5)
