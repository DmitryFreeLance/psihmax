# MAX-бот Оксаны Ремпе

Java 21 + Spring Boot бот для MAX: long polling, inline-кнопки типа `message`, YooKassa-платежи и webhook `payment.succeeded`.

## Что умеет

- Работает через long polling MAX API на `https://platform-api2.max.ru`.
- Все навигационные кнопки сделаны inline-кнопками типа `message`, внешние ссылки открываются URL-кнопками.
- Перед оплатой бот запрашивает имя и телефон.
- Создаёт платеж YooKassa и отправляет пользователю ссылку на оплату.
- При webhook `payment.succeeded` проверяет платеж через YooKassa API, благодарит пользователя и уведомляет администратора.
- Команда `/admin` позволяет текущему администратору добавить нового администратора кнопкой из пользователей, которые уже писали боту.
- Админы видят в главном меню кнопку «Админ-панель» и могут планировать ссылки для оплативших клиентов.
- Кнопка «Отзывы» делит отзывы на фото и видео: фото отправляются пачками по 9 из папки `otzivi`, видео — по одному из папки `videos`.
- При открытии раздела «Курс» бот отправляет текст вместе с видео `videos/kurs.mp4`.
- Заказы хранит в JSON-файле, поэтому повторный webhook не отправит повторное уведомление.
- Dockerfile импортирует сертификаты Минцифры `Russian Trusted Root CA` и `Russian Trusted Sub CA` в JVM truststore.

## Переменные окружения

Скопируйте `.env.example` в `.env` и заполните значения:

```bash
cp .env.example .env
```

Главные поля:

- `MAX_BOT_TOKEN` — токен MAX-бота.
- `MAX_ADMIN_USER_ID` — MAX user id администратора, которому отправлять уведомления.
- `YOOKASSA_SHOP_ID` и `YOOKASSA_SECRET_KEY` — данные магазина YooKassa.
- `YOOKASSA_RETURN_URL` — куда ЮKassa вернёт пользователя после оплаты.
- `REVIEWS_DIR` — папка с фото отзывов внутри контейнера, по умолчанию `/app/otzivi` для Docker-запуска ниже.
- `VIDEOS_DIR` — папка с видео внутри контейнера, по умолчанию `/app/videos` для Docker-запуска ниже.
- `BOT_TIME_ZONE` — часовой пояс для дат в админ-панели, по умолчанию `Europe/Moscow`.

Webhook в кабинете YooKassa:

```text
https://ваш-домен/webhooks/yookassa
```

Событие: `payment.succeeded`.

## Сборка

```bash
docker build -t psihmax-bot .
```

## Запуск

```bash
docker run -d \
  --name psihmax-bot \
  --restart unless-stopped \
  -p 127.0.0.1:8082:8080 \
  -e SERVER_PORT=8080 \
  -e MAX_BOT_TOKEN='<ТОКЕН_MAX_БОТА>' \
  -e MAX_API_BASE_URL='https://platform-api2.max.ru' \
  -e MAX_ADMIN_USER_ID='<MAX_USER_ID_ПЕРВОГО_АДМИНА>' \
  -e YOOKASSA_SHOP_ID='<SHOP_ID_ЮKASSA>' \
  -e YOOKASSA_SECRET_KEY='<SECRET_KEY_ЮKASSA>' \
  -e YOOKASSA_RETURN_URL='https://bot.umkarta.ru/psihmax/health' \
  -e BOOK_URL='https://ozon.ru/t/IF6xtqF' \
  -e VK_URL='https://vk.com/oksana_praktik' \
  -e TELEGRAM_URL='https://t.me/oksana_praktik' \
  -e BOT_DATA_FILE='/app/data/orders.json' \
  -e REVIEWS_DIR='/app/otzivi' \
  -e VIDEOS_DIR='/app/videos' \
  -e BOT_TIME_ZONE='Europe/Moscow' \
  -v psihmax-data:/app/data \
  psihmax-bot
```

## Админ-панель ссылок

Админ видит последней кнопкой в главном меню `🛠 Админ-панель`.

Доступные действия:

- `➕ Ссылка курса` — ссылка для полного курса, понедельной оплаты и повторного обучения.
- `➕ Ссылка личной сессии` — отдельная ссылка для оплативших личную сессию.
- `📋 Запланированные ссылки` — просмотр и удаление активных ссылок.

Логика отправки:

- Полный курс получает каждую ссылку курса.
- Понедельная оплата получает одну следующую ссылку курса за одну успешную оплату.
- Повторное обучение работает так же, как понедельная оплата: одна оплата — одна следующая ссылка.
- Личная сессия получает отдельные ссылки личной сессии.

Дата в админ-панели вводится в формате:

```text
дд.мм.гггг чч:мм
```

Например:

```text
25.07.2026 09:00
```

Проверка:

```bash
curl http://localhost:8080/health
```

## Локальный запуск без Docker

```bash
mvn spring-boot:run
```

Для локальной проверки webhook нужен публичный HTTPS-туннель, например `ngrok`, и URL вида:

```text
https://example.ngrok-free.app/webhooks/yookassa
```
