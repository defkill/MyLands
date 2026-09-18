# ТЕХНІЧНИЙ АУДИТ ТА ВИСНОВОК ДЛЯ CTO
**Проєкт:** Orientir / MyLands (Автономна тактична офлайн-навігація для Android)  
**Роль:** Senior Software Architect, Principal Security Engineer, Lead Code Reviewer  
**Дата проведення аудиту:** Вересень 2026 року  
**Версія кодової бази:** Ревізія main (19 000+ рядків Kotlin, Jetpack Compose, Room, Coroutines)  

---

## EXECUTIVE SUMMARY (РЕЗЮМЕ ДЛЯ CTO)

Комплексний аудит охопив архітектуру, якість коду, безпеку (OWASP Mobile Top 10, OWASP ASVS), надійність багатопотоковості, продуктивність, стан залежностей, CI/CD та тестове покриття додатку.

Додаток реалізує вузькоспеціалізовану, критично важливу функціональність: роботу в польових умовах при повній відсутності радіозв'язку (автономне картографування, PDR-пішохідне счислення, геодезичні перетворення SK-42/USK-2000/WGS-84, пряме читання векторних і растрових MBTiles, аналіз висот SRTM HGT).

### Загальні оцінки (шкала 1–10)

| Напрямок | Оцінка | Коментар архітектора |
|---|:---:|---|
| **Архітектура** | **5.5 / 10** | Монолітна структура модуля `:app`. Відсутній DI-фреймворк. Наявні виражені антипатерни "God Object" (`NavigationMainScreen`, `MainViewModel`). |
| **Безпека** | **5.0 / 10** | Збережені у VCS ключі `debug.keystore`, відсутність R8/Minify в Release, потенційний Gzip-Bomb DoS, небезпечний експорт кореня кешу через FileProvider, ризики блокування в Google Play через `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| **Якість коду** | **5.5 / 10** | Екстремально великі файли (до 1916 рядків), Primitive Obsession (збереження ID точок маршруту у вигляді CSV-рядка в БД), дублювання коду санітизації шляхів, заглушені винятки `catch (_: Exception) {}`. |
| **Продуктивність** | **6.0 / 10** | Повільний лінійний пошук у пам'яті по 30 000+ населених пунктах (1.9 МБ CSV), однопотокове вузьке місце для парсингу векторних тайлів, прапорець `largeHeap="true"` як компенсація неефективного управління пам'яттю. |
| **Супроводжуваність** | **4.5 / 10** | Повна відсутність документації (немає README, ADR, Onboarding), висока складність внесення змін у монолітні Composable-функції. |
| **Тестування** | **7.0 / 10** | Сильні unit-тести для геодезії, PDR, парсингу координат та безпеки файлів. Відсутність тестів UI, міграцій БД та фонових сервісів. |
| **DevOps / CI/CD** | **6.5 / 10** | Працюючий GitHub Actions workflow, але `continue-on-error: true` на етапі Lint, відсутність автоматизованого SAST/DAST сканування та перевірки підпису. |
| **ЗАГАЛЬНА ОЦІНКА** | **5.7 / 10** | **Високий технологічний потенціал за наявності критичного технічного боргу та вразливостей.** |

---

## 1. АРХІТЕКТУРНИЙ АНАЛІЗ

### 1.1 Структура проєкту та модульність
- **Монолітний модуль `:app`**: Увесь проєкт (19k LOC) зосереджений в одному Gradle-модулі. Відсутнє розділення на функціональні шари (`:core:geodesy`, `:core:database`, `:core:sensors`, `:feature:map`, `:feature:tracking`).
- **Наслідки**: Будь-яка локальна зміна призводить до повної перекомпіляції модулів KSP і Compose, збільшує час збірки та унеможливлює ізольоване повторне використання алгоритмічної бази (наприклад, ядра геодезії чи парсера MVT).

### 1.2 Зв'язність компонентів та принципи SOLID
- **DIP (Dependency Inversion Principle) — ГРУБЕ ПОРУШЕННЯ**:
  Клас `MainViewModel` (1481 рядок) напряму інстанціює репозиторії, менеджери датчиків та базу даних через явні виклики конструкторів:
  ```kotlin
  // MainViewModel.kt:52-59
  val database = AppDatabase.getInstance(application)
  val repository = NavigationRepository(database)
  val tileManager = TileManager(application)
  val settlementRepository = SettlementRepository(application)
  val locationTracker = LocationTracker.getInstance(application)
  val orientationManager = OrientationManager.getInstance(application)
  val stepDetectorManager = StepDetectorManager.getInstance(application)
  ```
  Це виключає можливість підміни реалізацій у тестах (за винятком глобальних змін стану Android Context) та створює жорстку прив'язку бізнес-логіки до платформних синглтонів.
- **SRP (Single Responsibility Principle) — ГРУБЕ ПОРУШЕННЯ**:
  - `MainViewModel.kt` одночасно управляє: станом карти, імпортом/експортом GPX/KML, счисленням PDR, геодезичними засічками, лінією видимості (Line-of-Sight), пошуком міст, діалоговими вікнами та кешуванням тайлів.
  - `NavigationMainScreen.kt` (1916 рядків): містить у собі весь інтерфейс додатку, рендеринг панелей, стан 10+ діалогових вікон, обробку зворотних викликів Activity Result Launchers, перевірку дозволів та форматування рядків.
- **OCP (Open/Closed Principle)**:
  Розширення підтримуваних форматів карт чи систем координат вимагає прямих модифікацій `when`-блоків у десятках місць коду (`OfflineMapDetector`, `MainViewModel`, `DataExchangeDialog`).

### 1.3 Архітектурні антипатерни
1. **God Object / God Composable**: `NavigationMainScreen` та `MainViewModel`.
2. **Static Mutable Cross-Process State**:
   У службах `MapBackupService` та `MapDownloadService` вхідні параметри передаються не через Intent Extras або базу даних, а через статичні змінні-поля в `companion object`:
   ```kotlin
   // MapBackupService.kt:78-79
   @Volatile private var pendingOutputFile: File? = null
   @Volatile private var pendingRestoreUri: Uri? = null
   ```
   **Сценарій збою:** Якщо ОС Android знищить процес сервісу через нестачу пам'яті (Low Memory Killer) і спробує перезапустити його, або якщо метод запуску буде викликаний повторно, статичні змінні будуть втрачені (`null`) або перезаписані невідповідними значеннями, що спричинить аварійне завершення роботи сервісу (`stopSelf()`).
3. **Primitive Obsession у персистентному шарі**:
   Зв'язок між маршрутом (`RouteEntity`) та його точками збережений як звичайний CSV-рядок:
   ```kotlin
   // RouteEntity.kt
   val waypointIdsCsv: String
   ```
   У реляційній моделі Room це призводить до відсутності перевірки Foreign Keys (CASCADE DELETE не працює). Видалення точки з бази залишає "биті" ID у маршруті, викликаючи розсинхронізацію при розрахунку плечей маршруту (`getRoutePoints`).

---

## 2. ЯКІСТЬ КОДУ (CODE QUALITY & CODE SMELLS)

### 2.1 Рейтинг занадто великих файлів
| Файл | Рядків | Критичність | Проблема |
|---|:---:|:---:|---|
| `NavigationMainScreen.kt` | 1916 | **КРИТИЧНА** | Порушення SRP, нерозділений монолітний UI, надлишковий скоуп рекомпозиції. |
| `MainViewModel.kt` | 1481 | **КРИТИЧНА** | Порушення SRP, змішування бізнес-логіки 8 різних доменів. |
| `TacticalMapView.kt` | 1044 | **ВИСОКА** | Складний низькорівневий Canvas-рендер, жести та обчислення координат в одному файлі. |
| `DataExchangeDialog.kt` | 864 | **ВИСОКА** | Змішування логіки SAF (Storage Access Framework), UI та синхронізації файлів. |
| `TrackingService.kt` | 721 | **СЕРЕДНЯ** | Велика кількість відповідальностей: датчики, WakeLock, AlarmManager, сповіщення. |

### 2.2 Деталізований реєстр дефектів коду
1. **Файл:** `app/src/main/java/com/example/map/TileManager.kt`, рядки 149–152  
   **Проблема:** Недостатня валідація Path Traversal під час імпорту `.orntpack`:
   ```kotlin
   val safeName = entry.name.replace("\\", "/").trimStart('/')
   if (safeName.contains("..")) return@forEachIndexed
   ```
   Перевірка через `contains("..")` не враховує специфічні символьні кодування файлових систем чи можливі символічні посилання. У методі `restoreFullBackup` використовується надійний патерн `targetFile.canonicalPath.startsWith(...)`, що є порушенням принципу **DRY**.
   **Критичність:** **ВИСОКА**.

2. **Файл:** `app/src/main/java/com/example/data/settlement/SettlementRepository.kt`, рядки 59–70  
   **Проблема:** Неефективний алгоритм пошуку та надмірне виділення пам'яті (Memory/GC Churn).
   Для кожного пошукового запиту проводиться ітерація по 30 000+ об'єктах з викликом `item.name.lowercase()` та `item.oblast.lowercase()`, створюючи десятки тисяч тимчасових об'єктів `String` на кожен введений символ.
   **Критичність:** **СЕРЕДНЯ**.

3. **Файл:** `app/src/main/java/com/example/map/TileManager.kt` (рядки 253, 267, 309, 315, 381) та `TrackingService.kt` (рядки 523, 544, 583, 608)  
   **Проблема:** Заглушення помилок порожніми блоками `catch (_: Exception) {}`.
   Приховує від розробників критичні помилки введення/виведення, заповнення диска чи виснаження файлових дескрипторів.
   **Критичність:** **СЕРЕДНЯ**.

4. **Файл:** `app/src/main/java/com/example/data/repository/NavigationRepository.kt`, рядки 90–95  
   **Проблема:** Порушення третьої нормальної форми (3NF) реляційної моделі баз даних (Anti-pattern Primitive Obsession).
   **Критичність:** **СЕРЕДНЯ**.

---

## 3. ПОТЕНЦІЙНІ ПОМИЛКИ, ДЕФЕКТИ ТА НАДІЙНІСТЬ

### 3.1 Сценарій відмови №1: DoS через GZIP-бомбу у вектоних тайлах
- **Локація:** `app/src/main/java/com/example/map/vector/MvtParser.kt`, рядок 115:
  ```kotlin
  fun decompressGzipIfNeeded(data: ByteArray): ByteArray {
      if (data.size < 2) return data
      val isGzip = (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B
      if (!isGzip) return data

      return try {
          GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
      } catch (_: Exception) {
          data
      }
  }
  ```
- **Сценарій відмови:**
  Зловмисник передає або користувач завантажує сторонній файл `.mbtiles`, у якому один із тайлів містить gzip-бомбу (наприклад, 100 КБ стиснутих нулів, які розгортаються у 2 ГБ).
  Виклик `it.readBytes()` не має обмеження на розмір читання з потоку. Перевірка `if (uncompressed.size > 20 * 1024 * 1024)` у рядку 94 викликається **ПІСЛЯ** того, як `readBytes()` спробує виділити гігабайти пам'яті в купі JVM.
- **Результат:** Негайний `OutOfMemoryError` і аварійне падіння процесу додатку.
- **Рекомендація з виправлення:**
  ```kotlin
  fun decompressGzipIfNeeded(data: ByteArray, maxAllowedBytes: Int = 20 * 1024 * 1024): ByteArray {
      if (data.size < 2) return data
      val isGzip = (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B
      if (!isGzip) return data

      val buffer = ByteArray(8192)
      val out = java.io.ByteArrayOutputStream()
      var totalRead = 0
      GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
          while (true) {
              val read = gzip.read(buffer)
              if (read <= 0) break
              totalRead += read
              if (totalRead > maxAllowedBytes) {
                  throw java.io.IOException("GZIP decompressed payload exceeded limit of $maxAllowedBytes bytes")
              }
              out.write(buffer, 0, read)
          }
      }
      return out.toByteArray()
  }
  ```

### 3.2 Сценарій відмови №2: Втрата параметрів фонових операцій при Low Memory Killer
- **Локація:** `MapBackupService.kt` (рядки 78–79, 149, 210) та `MapDownloadService.kt` (рядки 61–65).
- **Сценарій відмови:**
  Користувач запускає бекап чи завантаження регіону на 500 МБ. Система під час перемикання додатків або вимкненого екрану відчуває нестачу оперативної пам'яті та вбиває процес `com.example`. Система Android намагається відновити Foreground Service згідно з життєвим циклом, викликаючи `onStartCommand()`.
  Оскільки статичні поля `pendingOutputFile` та `pendingRestoreUri` ініціалізуються в `null`, сервіс читає `null`, негайно завершує роботу через `stopSelf()`, а користувач отримує незавершений бекап або зламаний архів без пояснення причин.
- **Рекомендація:** Передавати параметри суто через `Intent.putExtra()` або зберігати задачу в черзі Room/WorkManager.

### 3.3 Сценарій відмови №3: Розсинхронізація Foreign Key точок та маршрутів
- **Локація:** `RouteEntity.kt`, `NavigationRepository.kt:110-115`.
- **Сценарій відмови:**
  Користувач створює маршрут із точок ID `1, 2, 3`. Згодом точка з ID `2` видаляється користувачем у списку точок.
  Оскільки між `waypoints` та `routes` немає реляційної таблиці з `ON DELETE CASCADE` або зовнішніх ключів, у `RouteEntity.waypointIdsCsv` залишається `"1,2,3"`.
  При виклику `getRoutePoints()`:
  ```kotlin
  val points = waypointDao.getWaypointsByIds(ids).associateBy { it.id }
  return ids.mapNotNull { points[it] }
  ```
  Точка мовчки відкидається. Якщо в маршруті залишається менше 2 точок, розрахунок плечей `getRouteLegs` повертає порожній список, але маршрут залишається активним в UI, що призводить до некоректного відображення навігаційної стрілки.

---

## 4. АНАЛІЗ БЕЗПЕКИ (SECURITY AUDIT)
Оцінка базується на вимогах **OWASP Mobile Application Security (MASVS)** та **OWASP Top 10 Mobile**.

### 4.1 Критичні знахідки

#### [SEC-01] Збереження ключів підпису у сховищі контролю версій (CWE-312 / MASVS-STORAGE-1)
- **Файли:** `/debug.keystore`, `/debug.keystore.base64`
- **Рівень ризику:** **КРИТИЧНИЙ (CRITICAL)**
- **Опис:** Файли `debug.keystore` та його base64-представлення знаходяться у кореневій директорії репозиторію. У файлі `app/build.gradle.kts` (рядки 32–37) зафіксовано паролі за замовчуванням: `android` / `androiddebugkey`.
- **Сценарій атаки:** Будь-який сторонній розробник, що має доступ до репозиторію, може скомпілювати та підписати шкідливу версію APK тим самим ключем. Якщо користувач встановить підроблену версію через канал оновлення (наприклад, side-loading), Android дозволить оновлення зі збереженням усіх внутрішніх даних (`shared_preferences`, бази даних Room з координатами чутливих об'єктів).
- **Рекомендація:** Додати `*.keystore` та `*.jks` у `.gitignore`. Видалити бінарні ключі з історії git.

#### [SEC-02] Повна відсутність обфускації та мінімізації коду в релізі (MASVS-CODE-1)
- **Файл:** `app/build.gradle.kts`, рядки 41–45:
  ```kotlin
  buildTypes {
      release {
          isCrunchPngs = false
          isMinifyEnabled = false
          proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
          signingConfig = signingConfigs.getByName("release")
      }
  }
  ```
- **Рівень ризику:** **ВИСОКИЙ (HIGH)**
- **Опис:** `isMinifyEnabled = false` вимикає оптимізатор R8/ProGuard.
- **Сценарій атаки:** Утиліти типу `jadx` або `apktool` миттєво розпаковують релізний APK до оригінального коду Kotlin із збереженням назв функцій, приватних протоколів передачі координат (наприклад, бінарний формат `QrPayload`), та алгоритмів захисту.
- **Рекомендація:** Увімкнути `isMinifyEnabled = true`, `isShrinkResources = true` та налаштувати правила у `proguard-rules.pro`.

#### [SEC-03] Надмірний експорт директорій через FileProvider (CWE-732 / MASVS-STORAGE-2)
- **Файл:** `app/src/main/res/xml/file_paths.xml`, рядки 1–6:
  ```xml
  <paths xmlns:android="http://schemas.android.com/apk/res/android">
      <cache-path name="shared_cache" path="." />
      <external-cache-path name="shared_external_cache" path="." />
  </paths>
  ```
- **Рівень ризику:** **ВИСОКИЙ (HIGH)**
- **Опис:** Елемент `<cache-path name="shared_cache" path="." />` відкриває доступ до **всього** вмісту внутрішнього кешу додатку (`context.cacheDir`), включаючи тимчасові файли відновлення (`temp_restore_backup.zip`), проміжні файли висот та вивантажені дані.
- **Сценарій атаки:** Сторонній додаток, який отримує від користувача `content://`-посилання на експортований файл, може скористатися цим дозволом для читання інших конфіденційних тимчасових файлів додатку через маніпуляцію шляхами в URI.
- **Рекомендація:** Обмежити експорт виділеними піддиректоріями:
  ```xml
  <paths xmlns:android="http://schemas.android.com/apk/res/android">
      <cache-path name="shared_exports" path="exports/" />
  </paths>
  ```

#### [SEC-04] Ризик блокування у Google Play через заборонені дозволи (Google Play Policy Risk)
- **Файл:** `app/src/main/AndroidManifest.xml`, рядки 18–19:
  ```xml
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
  <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
  ```
- **Рівень ризику:** **ВИСОКИЙ (HIGH)**
- **Опис:** Політика Google Play Developer Program забороняє використання `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, окрім дуже вузького списку додатків (месенджери, медичний моніторинг). Додаток отримає автоматичну відмову під час публікації. Аналогічно дозвіл `SCHEDULE_EXACT_ALARM` на Android 13+ вимагає суворої верифікації (дозволено лише для будильників/таймерів). Для регулярних операцій у фоновому сервісі слід використовувати стандартні механізми Foreground Service або `WorkManager`.

### 4.2 Сильні сторони безпеки
- **XXE (XML External Entity)**: У класі `GpxKmlService.kt` функція `createSafeDocumentBuilderFactory()` безпечно налаштована — вимкнено `disallow-doctype-decl`, зовнішні загальні та параметричні сутності. Атаки типу XXE неможливі.
- **SQL Injection**: Усі виклики SQLite у `MbtilesTileSource` та Room використовують виключно параметризовані аргументи (`?`). Вразливостей SQLi не виявлено.
- **Path Traversal у бекапах**: Метод `TileManager.restoreFullBackup()` містить надійну перевірку канонічних шляхів (`targetFile.canonicalPath.startsWith(...)`).

---

## 5. АНАЛІЗ ЗАЛЕЖНОСТЕЙ ТА БІБЛІОТЕК

Аналіз проведено на базі `gradle/libs.versions.toml`:

```toml
agp = "9.1.1"
kotlin = "2.2.10"
composeBom = "2024.09.00"
room = "2.7.0"
zxingCore = "3.5.3"
cameraCamera2 = "1.5.0"
```

1. **Застаріла версія ZXing Core (3.5.3)**:
   Бібліотека `com.google.zxing:core` перебуває у режимі підтримки (maintenance mode) вже кілька років, написана на Java, створює багато проміжних об'єктів під час роботи з растром. Рекомендується міграція на більш швидкісні та легкі альтернативи (наприклад, локальний AndroidX Code Scanner або оптимізовані Kotlin-native декодери).
2. **Compose BOM `2024.09.00`**:
   Використовується стабільна версія дворічної давнини, яка не містить актуальних оптимізацій рекомпозиції Compose Runtime для Strong Skipping Mode (доступних у версіях 2025+).
3. **Невикористані або закоментовані плагіни**:
   Плагін `com.google.android.libraries.mapsplatform.secrets-gradle-plugin` (версія 2.0.1) оголошений у конфігурації, але жоден ключ Google Maps у коді не використовується (карти рендеряться виключно через власний движок `TacticalMapView`). Це надлишкова залежність, яка збільшує час синхронізації Gradle.

---

## 6. ПРОДУКТИВНІСТЬ (PERFORMANCE AUDIT)

### 6.1 Вузькі місця рендерингу та обчислень
1. **Штучне обмеження парсингу векторних тайлів до 1 потоку**:
   ```kotlin
   // TileManager.kt:38
   private val vectorParseDispatcher = Dispatchers.IO.limitedParallelism(1)
   ```
   **Проблема:** Хоча це запобігає піковим навантаженням на пам'ять при швидкому скролінгу карти, на сучасних 8-ядерних пристроях це призводить до значних затримок (зависань) при відмальовуванні векторних MBTiles, коли один важкий тайл блокує рендеринг решти суміжних тайлів.
   **Рекомендація:** Використовувати `Dispatchers.Default.limitedParallelism(2)` з обмеженням розміру буфера черги.

2. **Неконтрольований ріст кешу геометрії в пам'яті**:
   У `MbtilesTileSource` кеш `parsedTileCache` має розмір `maxMemory / 16`. При наявності великої кількості складних полігонів пам'ять фрагментується, що змушує розробника додавати `System.gc()` (рядок 202) та вимагати `android:largeHeap="true"` у маніфесті.
   **Рекомендація:** Зберігати попередньо спрощену геометрію (downsampled geometry) або використовувати квадродерево (R-Tree / QuadTree).

3. **Синхронний доступ до файлів рельєфу SRTM у UI-потоці**:
   У `ElevationEngine.getElevation()` викликається `synchronized(tileLock)`. Якщо під час переміщення карти фоновий потік розраховує профіль маршруту на 500 точок, графічний потік буде заблоковано на час доступу до `RandomAccessFile`, що спричинить тремтіння інтерфейсу (Jank / Dropped Frames).

---

## 7. DEVOPS ТА CI/CD

### 7.1 Аналіз `.github/workflows/android-build.yml`
- **Сильні сторони**:
  - Присутня матриця збірки Debug та Release.
  - Налаштовано кешування Gradle (`setup-gradle@v5`).
  - Є автоматичне вивантаження артефактів (APK/AAB).
- **Слабкі місця**:
  1. **Відсутній `gradle-wrapper.jar` у репозиторії**:
     Workflow змушений генерувати обгортку `gradle wrapper --gradle-version 9.3.1` на кожному запуску (рядки 55–58). Це призводить до зайвих витрат часу білд-агента.
  2. **Lint не блокує збірку**:
     ```yaml
     # android-build.yml:90-92
     - name: Lint
       continue-on-error: true
       run: ./gradlew lint --continue
     ```
     Помилки лінтера, включаючи попередження безпеки та сумісності з версіями Android API, ігноруються і не перешкоджають злиттю коду в `main`.
  3. **Відсутність перевірки безпеки (SAST)**:
     У пайплайні немає сканерів типу MobSF, SonarQube чи Trivy для контролю вразливих бібліотек.

---

## 8. АНАЛІЗ ТЕСТУВАННЯ (TESTING & QA)

### 8.1 Стан тестового покриття
У репозиторії є 14 тестових файлів (`app/src/test/java/com/example/`):
- `GeodesyEngineTest.kt` (9.5 KB) — чудове покриття геодезичних формул, прямої та оберненої геодезичних задач, розрахунку кутів і деклінації.
- `PdrAdaptiveBatchingTest.kt` (11 KB) — глибоке тестування алгоритму адаптивного батчингу кроків та фільтрації шуму GPS.
- `TrackingServiceBackgroundTest.kt` (15 KB) — надійне тестування життєвого циклу трекінгу під Robolectric.
- `DataExchangeSecurityTest.kt` (5.3 KB) — перевірка базової санітизації шляхів.

### 8.2 Критичні "білі плями" у тестах
1. **Відсутність тестів міграцій Room**:
   Хоча параметр `exportSchema = true` активовано, немає жодного тесту на базі `MigrationTestHelper`, який би перевіряв міграції схеми бази даних при додаванні нових колонок чи таблиць.
2. **Повна відсутність тестів користувацького інтерфейсу (UI/Compose)**:
   Єдиний скріншотний тест `greeting.png` не покриває складні екрани `NavigationMainScreen`, діалоги обміну даними та калькулятор засечок.
3. **Відсутність тестів на переповнення черги та OOM**:
   Немає негативних тестів із пошкодженими або надмірно великими файлами `.mbtiles` / `.hgt`.

---

## 9. ДОКУМЕНТАЦІЯ ТА АРХІТЕКТУРНІ РІШЕННЯ

- **Рівень документації:** **0 / 10 (КРИТИЧНО НЕЗАДОВІЛЬНИЙ)**.
- У репозиторії повністю відсутні:
  - `README.md` (немає інструкцій зі збірки, опису проєкту, мінімальних вимог).
  - Архітектурна схема або опис взаємодії модулів (Component Diagram).
  - `ADR` (Architecture Decision Records) — відсутні записи про те, чому обрано алгоритм PDR, власну реалізацію MVT-парсера чи структуру зберігання тайлів.
  - `SECURITY.md` — відсутня політика повідомлення про вразливості.

---

## 10. ПІДСУМКОВИЙ ЗВІТ ТА РЕКОМЕНДАЦІЇ ДЛЯ СТО

### 10.1 Таблиця критичних знахідок

| Severity | Компонент | Проблема | Рекомендація |
|:---:|---|---|---|
| **CRITICAL** | Безпека / VCS | `debug.keystore` та base64 у репозиторії | Видалити з git, додати у `.gitignore`, оновити ключі в CI Secrets |
| **HIGH** | Безпека / Build | `isMinifyEnabled = false` у Release | Увімкнути R8-обфускацію та налаштувати ProGuard |
| **HIGH** | Безпека / Парсер | Необмежена розпаковка GZIP у `MvtParser` (DoS/OOM) | Впровадити лічильник прочитаних байтів із жорстким лімітом (20 МБ) |
| **HIGH** | Безпека / Play Store | Дозволи `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM` | Видалити з маніфесту; замінити на стандартний Foreground Service та WorkManager |
| **HIGH** | Архітектура | God-об'єкти `NavigationMainScreen` (1916 LOC) та `MainViewModel` (1481 LOC) | Декомпозувати екран на субкомпоненти; розділити ViewModel за доменами |
| **HIGH** | Безпека / Файли | Експорт всього кешу `path="."` у `file_paths.xml` | Обмежити шлях експорту виділеною папкою `path="exports/"` |
| **MEDIUM** | Надійність | Статичні змінні параметрів у сервісах (`MapBackupService`) | Передавати параметри суто через `Intent.putExtra()` або базу даних |
| **MEDIUM** | База даних | Збереження ID точок маршруту через CSV-рядок у Room | Впровадити нормалізовану проміжну сутність зв'язку `RouteWaypointCrossRef` |
| **MEDIUM** | Продуктивність | Лінійний O(N) пошук по 30 000 населених пунктах у пам'яті | Перенести населені пункти у віртуальну таблицю Room SQLite FTS4/FTS5 |
| **MEDIUM** | Продуктивність | Однопотокове блокування парсингу векторних тайлів | Дозволити обмежений паралелізм (2 потоки) залежно від ядер CPU |

---

### 10.2 Топ-10 найкритичніших проблем (Risk Score Matrix)

Формула розрахунку: **Risk Score = Probability (1–5) × Impact (1–5)**

| № | Проблема | Ймовірність | Вплив | Risk Score |
|---|---|:---:|:---:|:---:|
| 1 | Збережені у VCS ключі підпису (`debug.keystore`) | 5 | 5 | **25 (Критичний)** |
| 2 | DoS / OOM через GZIP-бомбу у `MvtParser` | 4 | 5 | **20 (Високий)** |
| 3 | Відхилення додатку в Google Play через `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 5 | 4 | **20 (Високий)** |
| 4 | Відсутність обфускації (R8/Minify) у релізі | 5 | 4 | **20 (Високий)** |
| 5 | Втрата параметрів та аварійний стоп бекапу при нестачі RAM через static-поля | 4 | 4 | **16 (Високий)** |
| 6 | Небезпечний експорт усього кореня кешу через `FileProvider` | 3 | 4 | **12 (Середній)** |
| 7 | Збої цілісності даних маршрутів через CSV-збереження зв'язків у Room | 4 | 3 | **12 (Середній)** |
| 8 | Падіння UI (Jank) через лінійний пошук міст та синхронні блокування рельєфу | 4 | 3 | **12 (Середній)** |
| 9 | Проблеми супроводжуваності через надвеликі файли (1900+ рядків у Composable) | 5 | 2 | **10 (Середній)** |
| 10 | Ігнорування помилок лінтера в CI (`continue-on-error: true`) | 4 | 2 | **8 (Середній)** |

---

### 10.3 План виправлення (Roadmap & Remediation Plan)

#### Етап 1: Quick Wins (< 1–2 дні)
- [ ] Видалити `debug.keystore` та `debug.keystore.base64` з репозиторію; додати їх у `.gitignore`.
- [ ] Виправити вразливість GZIP у `MvtParser.kt`, додавши ліміт на потік розпаковки.
- [ ] Звузити шляхи доступу у `app/src/main/res/xml/file_paths.xml`.
- [ ] Видалити `continue-on-error: true` для кроку `Lint` у GitHub Actions.
- [ ] Увімкнути `isMinifyEnabled = true` та `isShrinkResources = true` для Release-збірки.

#### Етап 2: Short Term (1–2 тижні)
- [ ] Відмовитися від `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` та `SCHEDULE_EXACT_ALARM`, адаптувавши поведінку сервісів під суворі вимоги Google Play.
- [ ] Переписати запуск `MapBackupService` та `MapDownloadService`, передаючи параметри виключно через `Intent`.
- [ ] Створити документацію: базовий `README.md`, інструкцію з розгортання та архітектурний огляд.
- [ ] Додати збереження `gradle-wrapper.jar` у Git LFS або репозиторій для прискорення CI.

#### Етап 3: Medium Term (1–2 місяці)
- [ ] Рефакторинг `NavigationMainScreen.kt`: розбити екран на окремі файли-компоненти (`MapViewport`, `ControlPanels`, `DialogHost`, `NavigationOverlays`).
- [ ] Рефакторинг `MainViewModel.kt`: розділити на цільові в'юмоделі (`MapViewModel`, `TrackingViewModel`, `ElevationViewModel`, `ExchangeViewModel`) або впровадити доменні UseCase.
- [ ] Міграція зв'язку `RouteEntity` -> `WaypointEntity` на нормалізовану реляційну схему Room зі зовнішніми ключами.
- [ ] Перенесення бази населених пунктів із CSV у вбудовану повнотекстову базу даних SQLite FTS5.

#### Етап 4: Long Term (> 2 місяців)
- [ ] Модуляризація архітектури: розділення моноліту `:app` на мультипроєктну структуру (`:core:geodesy`, `:core:database`, `:core:mvt`, `:feature:tracking`).
- [ ] Впровадження ін'єкції залежностей (Hilt або Koin) для усунення жорсткого зв'язування.
- [ ] Повне покриття UI за допомогою Robolectric та Roborazzi screenshot-тестів.

---

### ЗАГАЛЬНИЙ ТЕХНІЧНИЙ ВИСНОВОК ДЛЯ СТО
Проєкт демонструє **високий інженерний рівень математичного ядра та автономних алгоритмів**: розрахунки геодезичних датумів (SK-42, USK-2000), робота з SRTM-висотами, парсинг MVT та пішохідне счислення PDR виконані на глибокому технічному рівні та добре покриті модульними тестами.

Водночас додаток страждає від **типових проблем швидкої прототипізації**: відсутність модульності, концентрація тисяч рядків коду в окремих файлах-гігантах та наявність кількох критичних прогалин у безпеці (зокрема зберігання ключів у репозиторії та потенційний DoS у парсері).

**Вердикт:** Додаток готовий до демонстрації як функціональний прототип, але **не готовий до безпечного корпоративного розгортання чи публікації в Google Play без виконання робіт з Етапу 1 (Quick Wins) та видалення невідповідних системних дозволів**. Початок робіт за планом виправлення дозволить знизити загальний ризик із високого до прийнятного протягом 2 тижнів.
