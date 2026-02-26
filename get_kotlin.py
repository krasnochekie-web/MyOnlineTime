import urllib.request

url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.3.72/kotlin-stdlib-1.3.72.jar"
filename = "kotlin-stdlib.jar"

print("Скачиваю старый добрый Kotlin...")
urllib.request.urlretrieve(url, filename)
print("Готово!")
