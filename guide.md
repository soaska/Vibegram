# Руководство по сборке APK для Cherrygram

## Предварительные требования

1. Установленный Docker
2. Установленный Docker Compose
3. Git
4. Доступ к репозиторию Cherrygram

## Подготовка к сборке

1. Клонируйте репозиторий:
```bash
git clone https://github.com/arsLan4k1390/Cherrygram.git
cd Cherrygram
```

2. Создайте файл `secrets_for_ci.env` в корневой директории проекта со следующим содержимым:
```
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

3. Убедитесь, что у вас есть файл keystore в директории `keystore/`. Если нет, создайте его:
```bash
mkdir -p keystore
keytool -genkey -v -keystore keystore/cherrygram.keystore -alias cherrygram -keyalg RSA -keysize 2048 -validity 10000
```

## Сборка APK

1. Запустите сборку с помощью Docker Compose:
```bash
sudo docker-compose up --build
```

Это запустит процесс сборки, который включает:
- Установку Android SDK
- Принятие лицензий Android SDK
- Установку необходимых компонентов SDK
- Сборку релизной версии APK

## Результаты сборки

После успешной сборки, APK файл будет находиться в директории:
```
app/build/outputs/apk/release/app-release.apk
```

## Возможные проблемы и их решения

1. **Ошибка с правами доступа к Docker**
   - Убедитесь, что у вас есть права на выполнение Docker команд
   - При необходимости добавьте вашего пользователя в группу docker:
   ```bash
   sudo usermod -aG docker $USER
   ```

2. **Ошибка с keystore**
   - Проверьте правильность паролей в `secrets_for_ci.env`
   - Убедитесь, что keystore файл находится в правильной директории

3. **Ошибка с памятью при сборке**
   - Увеличьте доступную память для Docker в настройках Docker Desktop
   - Или добавьте параметр `-Xmx2g` в команду сборки gradle

## Дополнительная информация

- Сборка использует Android SDK 33
- Используется NDK версии 25.1.8937393
- Gradle кэш сохраняется в Docker volume для ускорения последующих сборок

## Очистка

Для очистки кэша и временных файлов:
```bash
sudo docker-compose down -v
./gradlew clean
``` 