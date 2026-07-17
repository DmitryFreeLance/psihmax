package ru.psihmax.bot;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.psihmax.bot.config.BotProperties;
import ru.psihmax.bot.max.InlineButton;
import ru.psihmax.bot.max.MaxApiClient;
import ru.psihmax.bot.payment.CreatedPayment;
import ru.psihmax.bot.payment.PaymentRequest;
import ru.psihmax.bot.payment.Tariff;
import ru.psihmax.bot.payment.YooKassaClient;
import ru.psihmax.bot.store.AdminStore;
import ru.psihmax.bot.store.OrderStore;
import ru.psihmax.bot.store.PaymentOrder;
import ru.psihmax.bot.store.UserProfile;

@Service
public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final MaxApiClient maxApiClient;
    private final YooKassaClient yooKassaClient;
    private final OrderStore orderStore;
    private final AdminStore adminStore;
    private final ReviewService reviewService;
    private final BotProperties properties;
    private final Map<Long, UserState> states = new ConcurrentHashMap<>();

    public BotService(MaxApiClient maxApiClient, YooKassaClient yooKassaClient, OrderStore orderStore, AdminStore adminStore, ReviewService reviewService, BotProperties properties) {
        this.maxApiClient = maxApiClient;
        this.yooKassaClient = yooKassaClient;
        this.orderStore = orderStore;
        this.adminStore = adminStore;
        this.reviewService = reviewService;
        this.properties = properties;
    }

    public void handleUpdate(JsonNode update) {
        Incoming incoming = parse(update);
        if (incoming.userId() == null) {
            log.debug("Skip update without user id: {}", update);
            return;
        }
        adminStore.rememberUser(incoming.userId(), incoming.userDisplayName());

        try {
            if (incoming.callbackId() != null) {
                maxApiClient.answerCallback(incoming.callbackId(), "Готово");
            }
            if (incoming.payload() != null && !incoming.payload().isBlank()) {
                handlePayload(incoming);
                return;
            }
            handleText(incoming);
        } catch (Exception e) {
            log.warn("Cannot handle update {}", update, e);
            sendTo(incoming, "Не получилось выполнить действие. Попробуйте ещё раз или вернитесь в меню.", mainMenu());
        }
    }

    public void notifyPaymentSucceeded(PaymentOrder order) {
        sendToUser(order.userId(), """
                ✨ Оплата прошла успешно!

                Спасибо, %s. Вы оплатили: *%s*.

                Администратор получил уведомление и свяжется с вами, если нужно согласовать детали.
                """.formatted(order.customerName(), order.tariffTitle()), mainMenu());

        for (Long adminId : adminStore.adminIds()) {
            sendToUser(adminId, """
                    ✅ Новая оплата

                    Тариф: *%s*
                    Сумма: %s руб.
                    Имя: %s
                    Телефон: %s
                    MAX user id: `%d`
                    Профиль: %s
                    YooKassa payment id: `%s`
                    """.formatted(
                            order.tariffTitle(),
                            order.amountValue(),
                            order.customerName(),
                            order.phone(),
                            order.userId(),
                            blankToDash(order.userDisplayName()),
                            order.paymentId()
                    ), List.of(List.of(InlineButton.callback("🏠 Меню бота", "MAIN"))));
        }
    }

    private void handleText(Incoming incoming) {
        String text = incoming.text() == null ? "" : incoming.text().trim();
        UserState state = states.get(incoming.userId());

        if ("/start".equalsIgnoreCase(text) || "start".equalsIgnoreCase(text)) {
            states.remove(incoming.userId());
            sendStart(incoming);
            return;
        }

        if ("/admin".equalsIgnoreCase(text)) {
            states.remove(incoming.userId());
            sendAdminMenu(incoming);
            return;
        }

        if (state != null && state.step() == Step.WAITING_NAME) {
            if (text.length() < 2) {
                sendTo(incoming, "Напишите, пожалуйста, имя чуть подробнее.", backToTariffs());
                return;
            }
            states.put(incoming.userId(), state.withName(text));
            sendTo(incoming, """
                    Спасибо, %s.

                    Теперь отправьте номер телефона для связи. Можно в любом удобном формате.
                    """.formatted(text), backToTariffs());
            return;
        }

        if (state != null && state.step() == Step.WAITING_PHONE) {
            if (text.length() < 6) {
                sendTo(incoming, "Похоже, номер слишком короткий. Отправьте телефон ещё раз.", backToTariffs());
                return;
            }
            createPayment(incoming, state.withPhone(text));
            return;
        }

        sendTo(incoming, "Я рядом и отвечаю через кнопки ниже. Выберите нужный раздел.", mainMenu());
    }

    private void handlePayload(Incoming incoming) {
        String payload = incoming.payload();
        if ("MAIN".equals(payload)) {
            states.remove(incoming.userId());
            sendStart(incoming);
            return;
        }
        if ("ABOUT".equals(payload)) {
            sendTo(incoming, aboutText(), rows(
                    List.of(InlineButton.callback("✨ Курс", "COURSE"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("🔗 Ссылки", "LINKS"), InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
            return;
        }
        if ("COURSE".equals(payload)) {
            sendTo(incoming, courseText(), rows(
                    List.of(InlineButton.callback("💳 Выбрать формат", "TARIFFS")),
                    List.of(InlineButton.callback("⚠️ Правила пространства", "RULES")),
                    List.of(InlineButton.callback("↩️ Назад", "MAIN"))
            ));
            return;
        }
        if ("TARIFFS".equals(payload)) {
            states.remove(incoming.userId());
            sendTariffs(incoming);
            return;
        }
        if ("SESSION_INFO".equals(payload)) {
            sendTo(incoming, sessionText(), rows(
                    List.of(InlineButton.callback("🌀 Записаться на сессию", buyPayload(Tariff.SESSION))),
                    List.of(InlineButton.callback("💳 Все тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("↩️ Назад", "MAIN"))
            ));
            return;
        }
        if ("RULES".equals(payload)) {
            sendTo(incoming, rulesText(), rows(
                    List.of(InlineButton.callback("💳 Выбрать оплату", "TARIFFS")),
                    List.of(InlineButton.callback("↩️ Назад к курсу", "COURSE"), InlineButton.callback("🏠 Меню", "MAIN"))
            ));
            return;
        }
        if ("LINKS".equals(payload)) {
            sendTo(incoming, "Полезные ссылки Оксаны:", rows(
                    List.of(InlineButton.link("📘 Заказать книгу", properties.links().bookUrl())),
                    List.of(InlineButton.link("💙 VK", properties.links().vkUrl()), InlineButton.link("✈️ Telegram", properties.links().telegramUrl())),
                    List.of(InlineButton.callback("↩️ Назад", "MAIN"))
            ));
            return;
        }
        if ("REVIEWS".equals(payload)) {
            sendReviewPage(incoming, 0);
            return;
        }
        if (payload.startsWith("REVIEWS:")) {
            sendReviewPage(incoming, parsePage(payload.substring("REVIEWS:".length())));
            return;
        }
        if ("ADMIN_MENU".equals(payload)) {
            sendAdminMenu(incoming);
            return;
        }
        if (payload.startsWith("ADMIN_ADD:")) {
            addAdminByPayload(incoming, payload);
            return;
        }
        if (payload.startsWith("BUY:")) {
            Tariff.byCode(payload.substring("BUY:".length())).ifPresentOrElse(
                    tariff -> startPaymentDialog(incoming, tariff),
                    () -> sendTariffs(incoming)
            );
            return;
        }

        sendStart(incoming);
    }

    private void startPaymentDialog(Incoming incoming, Tariff tariff) {
        states.put(incoming.userId(), new UserState(Step.WAITING_NAME, tariff, null, null));
        sendTo(incoming, """
                Вы выбрали: *%s*
                Стоимость: *%s*

                Перед оплатой напишите, пожалуйста, ваше имя.
                """.formatted(tariff.title(), tariff.amountText()), backToTariffs());
    }

    private void createPayment(Incoming incoming, UserState state) {
        PaymentRequest request = new PaymentRequest(
                incoming.userId(),
                incoming.chatId(),
                incoming.userDisplayName(),
                state.name(),
                state.phone(),
                state.tariff()
        );
        CreatedPayment payment = yooKassaClient.createPayment(request);

        PaymentOrder order = new PaymentOrder(
                payment.paymentId(),
                incoming.userId(),
                incoming.chatId(),
                incoming.userDisplayName(),
                state.name(),
                state.phone(),
                state.tariff().code(),
                state.tariff().title(),
                state.tariff().amount().setScale(2).toPlainString(),
                payment.confirmationUrl(),
                "PENDING",
                Instant.now().toString(),
                null
        );
        orderStore.save(order);
        states.remove(incoming.userId());

        sendTo(incoming, """
                Готово, %s. Ссылка на оплату сформирована.

                Тариф: *%s*
                Сумма: *%s*

                После успешной оплаты бот получит вебхук от ЮKassa, поблагодарит вас и отправит уведомление админу.
                """.formatted(state.name(), state.tariff().title(), state.tariff().amountText()), rows(
                List.of(InlineButton.link("💳 Оплатить", payment.confirmationUrl())),
                List.of(InlineButton.callback("💎 Выбрать другой тариф", "TARIFFS")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        ));
    }

    private void sendStart(Incoming incoming) {
        sendTo(incoming, startText(), mainMenu());
    }

    private void sendTariffs(Incoming incoming) {
        sendTo(incoming, tariffsText(), rows(
                List.of(InlineButton.callback("🔥 Полный курс 35 000 ₽", buyPayload(Tariff.FULL))),
                List.of(InlineButton.callback("💸 Неделя 3 500 ₽", buyPayload(Tariff.WEEKLY))),
                List.of(InlineButton.callback("🌀 Личная сессия 15 000 ₽", buyPayload(Tariff.SESSION))),
                List.of(InlineButton.callback("🔁 Повторное обучение 1 000 ₽", buyPayload(Tariff.REPEAT))),
                List.of(InlineButton.callback("⚠️ Правила", "RULES"), InlineButton.callback("↩️ Назад", "MAIN"))
        ));
    }

    private void sendReviewPage(Incoming incoming, int page) {
        reviewService.getPage(page).ifPresentOrElse(review -> {
            int current = review.page() + 1;
            int previous = Math.max(0, review.page() - 1);
            int next = Math.min(review.total() - 1, review.page() + 1);

            List<List<InlineButton>> buttons = rows(
                    List.of(
                            InlineButton.callback("⬅️ Назад", "REVIEWS:" + previous),
                            InlineButton.callback(current + "/" + review.total(), "REVIEWS:" + review.page()),
                            InlineButton.callback("Вперёд ➡️", "REVIEWS:" + next)
                    ),
                    List.of(InlineButton.callback("💳 Тарифы", "TARIFFS"), InlineButton.callback("🏠 Меню", "MAIN"))
            );
            sendImageTo(incoming, "💬 *Отзывы*\nФото " + current + " из " + review.total(), review.token(), buttons);
        }, () -> sendTo(incoming, """
                💬 Отзывы пока не найдены.

                Проверьте, что папка `otzivi` существует и в ней есть фото.
                """, mainMenu()));
    }

    private void sendAdminMenu(Incoming incoming) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Команда `/admin` доступна только администраторам.", mainMenu());
            return;
        }

        List<UserProfile> users = adminStore.knownUsers();
        if (users.isEmpty()) {
            sendTo(incoming, "Пока нет пользователей, которых можно добавить в админы.", mainMenu());
            return;
        }

        List<List<InlineButton>> buttons = new ArrayList<>();
        users.stream().limit(30).forEach(user -> {
            String marker = adminStore.isAdmin(user.userId()) ? " ✅" : "";
            buttons.add(List.of(InlineButton.callback("👤 " + shortLabel(user.displayName(), user.userId()) + marker, "ADMIN_ADD:" + user.userId())));
        });
        buttons.add(List.of(InlineButton.callback("🏠 Главное меню", "MAIN")));

        sendTo(incoming, """
                🛠 *Админы бота*

                Выберите пользователя из тех, кто уже писал боту. После нажатия он начнёт получать уведомления об оплатах.
                """, buttons);
    }

    private void addAdminByPayload(Incoming incoming, String payload) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Команда доступна только администраторам.", mainMenu());
            return;
        }

        try {
            long userId = Long.parseLong(payload.substring("ADMIN_ADD:".length()));
            adminStore.addAdmin(userId);
            String label = adminStore.findUser(userId)
                    .map(user -> shortLabel(user.displayName(), user.userId()))
                    .orElse("Пользователь " + userId);
            sendTo(incoming, "✅ " + label + " добавлен в админы.", rows(
                    List.of(InlineButton.callback("🛠 Назад к админам", "ADMIN_MENU")),
                    List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
            sendToUser(userId, "✅ Вам выдали права администратора в боте Оксаны Ремпе.", mainMenu());
        } catch (NumberFormatException e) {
            sendAdminMenu(incoming);
        }
    }

    private void sendTo(Incoming incoming, String text, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendMessageToChat(incoming.chatId(), text, buttons);
        } else {
            maxApiClient.sendMessageToUser(incoming.userId(), text, buttons);
        }
    }

    private void sendImageTo(Incoming incoming, String text, String imageToken, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendImageToChat(incoming.chatId(), text, imageToken, buttons);
        } else {
            maxApiClient.sendImageToUser(incoming.userId(), text, imageToken, buttons);
        }
    }

    private void sendToUser(long userId, String text, List<List<InlineButton>> buttons) {
        maxApiClient.sendMessageToUser(userId, text, buttons);
    }

    private List<List<InlineButton>> mainMenu() {
        return rows(
                List.of(InlineButton.callback("🌌 О практике", "ABOUT"), InlineButton.callback("✨ Курс", "COURSE")),
                List.of(InlineButton.callback("💬 Отзывы", "REVIEWS"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                List.of(InlineButton.callback("🌀 Личная сессия", "SESSION_INFO")),
                List.of(InlineButton.callback("⚠️ Правила", "RULES"), InlineButton.callback("🔗 Ссылки", "LINKS"))
        );
    }

    private List<List<InlineButton>> backToTariffs() {
        return rows(
                List.of(InlineButton.callback("💳 К тарифам", "TARIFFS")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        );
    }

    @SafeVarargs
    private final List<List<InlineButton>> rows(List<InlineButton>... rows) {
        return List.of(rows);
    }

    private static String buyPayload(Tariff tariff) {
        return "BUY:" + tariff.code();
    }

    private int parsePage(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String startText() {
        return """
                Оксана Ремпе
                *Кармический психолог, регрессолог, гипнолог, мастер Рейки.*

                Видящая. Чувствующая. Знающая.

                Но это всё просто иллюзия. На самом деле я То, что это Осознаёт.

                Здесь можно узнать о курсе «Познай себя. Пробуждение», выбрать формат участия, оплатить обучение или записаться на личную сессию.
                """;
    }

    private String aboutText() {
        return """
                🌌 *Оксана Ремпе*

                Практика Оксаны о возвращении к себе: к честности, глубине, внутреннему знанию и живому контакту с тем, кто вы есть на самом деле.

                Регрессология, гипноз, Рейки, тетахилинг и аксесс-бары здесь не как ярлыки, а как инструменты для мягкого, точного погружения.
                """;
    }

    private String courseText() {
        return """
                ✨ *Курс «Познай себя. Пробуждение»*

                Для тех, кто устал искать истину снаружи и готов идти в себя. Для тех, кто хочет выйти из состояния жертвы обстоятельств и стать автором своей реальности.

                Обучение длится 13 недель: вы оплачиваете 12 недель, а 13-я неделя идёт в подарок.

                Это путь к осознаванию: не объяснить словами, а прожить самому.
                """;
    }

    private String tariffsText() {
        return """
                💳 *Выберите формат участия*

                🔥 *Полный курс* — 35 000 ₽
                Максимальная выгода: вся программа целиком, без еженедельных оплат.

                💸 *Понедельная оплата* — 3 500 ₽
                Оплата каждую пятницу до 21:00. Занятия по субботам в 9:00 по Москве.

                🌀 *Индивидуальная сессия* — 15 000 ₽
                Личная консультация и глубокая работа с причиной повторяющегося сценария.

                🔁 *Повторное обучение* — 1 000 ₽
                Для тех, кто уже проходил обучение.
                """;
    }

    private String sessionText() {
        return """
                🌀 *Индивидуальная сессия*

                «Один сеанс, который разделит вашу жизнь на до и после».

                Если вы снова попадаете в одинаковые отношения, упираетесь в финансовый потолок или годами ходите по кругу вокруг одной проблемы, мы ищем не поверхность, а корень.

                За 2 часа спускаемся к первопричине в подсознании и развязываем узел. Это не магия, а глубокая психологическая работа с памятью и состоянием.

                Стоимость: *15 000 ₽*
                """;
    }

    private String rulesText() {
        return """
                ⚠️ *Правила пространства*

                Участие подтверждается после фактического зачисления средств. Скриншоты и обещания в личные сообщения не принимаются: всё прозрачно и проходит через банк.

                Для еженедельного формата оплата следующей недели вносится до пятницы, 21:00.

                Если оплата не поступает вовремя, участие в следующей неделе не подтверждается. Пробуждение начинается с дисциплины и взаимного уважения.
                """;
    }

    private String shortLabel(String displayName, long userId) {
        String label = displayName == null || displayName.isBlank() ? "Пользователь " + userId : displayName;
        if (label.length() <= 32) {
            return label;
        }
        return label.substring(0, 29) + "...";
    }

    private Incoming parse(JsonNode update) {
        String updateType = firstTextField(update, "update_type", "type").orElse("");
        JsonNode message = update.path("message");
        JsonNode callback = update.path("callback");

        Long userId = firstLong(
                update.path("user").path("user_id"),
                update.path("user").path("id"),
                message.path("sender").path("user_id"),
                message.path("sender").path("id"),
                message.path("author").path("user_id"),
                message.path("author").path("id"),
                callback.path("user").path("user_id"),
                callback.path("user").path("id"),
                update.path("sender").path("user_id"),
                update.path("sender").path("id")
        ).orElse(null);

        Long chatId = firstLong(
                update.path("chat_id"),
                message.path("chat_id"),
                message.path("recipient").path("chat_id"),
                callback.path("message").path("chat_id")
        ).orElse(null);

        String text = firstText(
                message.path("body").path("text"),
                message.path("text"),
                update.path("text")
        ).orElse("");

        String callbackId = firstText(
                callback.path("callback_id"),
                update.path("callback_id")
        ).orElse(null);

        String payload = firstText(
                callback.path("payload"),
                update.path("payload")
        ).orElse(null);

        String displayName = firstText(
                update.path("user").path("name"),
                update.path("user").path("username"),
                message.path("sender").path("name"),
                message.path("sender").path("username"),
                callback.path("user").path("name"),
                callback.path("user").path("username")
        ).orElse("");

        if ("bot_started".equals(updateType) && payload == null) {
            payload = "MAIN";
        }
        return new Incoming(userId, chatId, text, callbackId, payload, displayName);
    }

    private Optional<String> firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText("").isBlank()) {
                return Optional.of(node.asText());
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstTextField(JsonNode root, String... fields) {
        for (String field : fields) {
            Optional<String> value = firstText(root.path(field));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<Long> firstLong(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                if (node.canConvertToLong()) {
                    return Optional.of(node.asLong());
                }
                String value = node.asText("");
                if (!value.isBlank()) {
                    try {
                        return Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // Try next field.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record Incoming(Long userId, Long chatId, String text, String callbackId, String payload, String userDisplayName) {
    }

    private enum Step {
        WAITING_NAME,
        WAITING_PHONE
    }

    private record UserState(Step step, Tariff tariff, String name, String phone) {
        UserState withName(String name) {
            return new UserState(Step.WAITING_PHONE, tariff, name, phone);
        }

        UserState withPhone(String phone) {
            return new UserState(step, tariff, name, phone);
        }
    }
}
