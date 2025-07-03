package ru.giv13;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Класс для API ГИС МТ
 * <p>Имплементирует AutoCloseable для автоматической остановки периодического сброса
 */
public class CrptApi implements AutoCloseable {
    // Потокобезопасный Singleton
    private static volatile CrptApi instance;

    // Базовый адрес API
    private final String apiUrl;

    // Потокобезопасное ограничение на количество запросов к API
    private final Semaphore semaphore;
    private ScheduledExecutorService scheduler;

    // Сериализация/десериализация объектов
    private final ObjectMapper mapper;

    // Потокобезопасный Token
    private volatile String token;
    private volatile boolean authFailed;

    /**
     * Конструктор потокобезопасного Singleton
     */
    private CrptApi(String apiUrl, int requestLimit) {
        this.apiUrl = apiUrl;
        this.semaphore = new Semaphore(requestLimit);
        this.mapper = new ObjectMapper();
    }

    /**
     * Экземпляр потокобезопасного Singleton
     */
    public static CrptApi getInstance(String url, TimeUnit timeUnit, int requestLimit) {
        if (instance == null) {
            synchronized (CrptApi.class) {
                if (instance == null) {
                    instance = new CrptApi(url, requestLimit);
                }
            }
        }
        // При каждом вызове метода обновляем authFailed и запускаем периодический сброс ограничения на количество запросов к API
        instance.authFailed = false;
        instance.scheduler = Executors.newSingleThreadScheduledExecutor();
        instance.scheduler.scheduleAtFixedRate(
                () -> instance.semaphore.release(requestLimit - instance.semaphore.availablePermits()), 0, 1, timeUnit
        );
        return instance;
    }

    /**
     * Остановка периодического сброса
     */
    @Override
    public void close() {
        scheduler.shutdown();
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ
     */
    public String lkDocumentsCreateLpIntroduceGoods(ProductGroup productGroup, ProductDocument productDocument, String signature) {
        LkDocumentsCreateBody body = new LkDocumentsCreateBody(DocumentFormat.MANUAL, productDocument, productGroup, signature, DocumentType.LP_INTRODUCE_GOODS);
        return lkDocumentsCreate(body);
    }

    /**
     * Единый метод создания документов
     */
    private String lkDocumentsCreate(LkDocumentsCreateBody body) {
        String path = "/lk/documents/create";
        Map<String, String> uriParams = new HashMap<>(Map.of("pg", body.getProductGroup().toString()));
        return makeHttpRequest(path, RequestMethod.POST, uriParams, body, "value");
    }

    /**
     * Запрос авторизаций
     */
    private String authCertKey() {
        return makeHttpRequest("/auth/cert/key", RequestMethod.GET, new HashMap<>(), null, null, false);
    }

    /**
     * Получение аутентификационного токена
     */
    private String authCert(String authCertKey) {
        return makeHttpRequest("/auth/cert/", RequestMethod.POST, new HashMap<>(), authCertKey, "token", false);
    }

    private String makeHttpRequest(String path, RequestMethod method, Map<String, String> uriParams, Object body, String responseKey) {
        return makeHttpRequest(path, method, uriParams, body, responseKey, true);
    }

    /**
     * Общий метод для API-запросов
     */
    private String makeHttpRequest(String path, RequestMethod method, Map<String, String> uriParams, Object body, String responseKey, boolean withToken) {
        try {
            semaphore.acquire();

            // Потокобезопасное получение токена
            // Если токен не задан, остальные потоки ожидают поток, в котором происходит получение токена
            if (withToken) {
                if (token == null && !authFailed) {
                    synchronized (this) {
                        if (token == null && !authFailed) {
                            token = authCert(authCertKey());
                            if (token == null) {
                                authFailed = true;
                                throw new Exception("Failed to get token");
                            }
                        }
                    }
                }
                // Если получение токена завершилось с ошибкой, все запросы в остальных потоках также автоматически вернут ошибку
                if (authFailed) {
                    throw new Exception("Failed to get token in another thread");
                }
            }

            String uri = apiUrl + path;
            // Добавляем URI-параметры к URI
            if (!uriParams.isEmpty()) {
                uri += uriParams.entrySet().stream().map(param -> param.getKey() + "=" + param.getValue()).collect(Collectors.joining("&", "?", ""));
            }

            // Формируем тело запроса
            HttpRequest.BodyPublisher publisher;
            if (body == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            } else {
                String json = body instanceof String ? (String) body : mapper.writer().writeValueAsString(body);
                publisher = HttpRequest.BodyPublishers.ofString(json);
            }

            // Формируем и отправляем HTTP-запрос
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Content-Type", "application/json")
                    .method(method.toString(), publisher);
            if (withToken) {
                builder.header("Authorization", "Bearer " + token);
            }
            // noinspection resource
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());

            // Если ответ успешный
            if (response.statusCode() == 200) {
                if (responseKey == null) {
                    return response.body();
                } else {
                    Map<String, String> object = mapper.readValue(response.body(), new TypeReference<>() {});
                    return object.get(responseKey);
                }
            }

            // Если ответ неуспешный
            throw new Exception(response.statusCode() + " -> " + response.body());
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
        return null;
    }

    /**
     * Тело запроса для единого метода создания документов
     */
    @Getter
    private class LkDocumentsCreateBody {
        private final DocumentFormat documentFormat;
        private final String productDocument;
        private final ProductGroup productGroup;
        private final String signature;
        private final DocumentType type;

        public LkDocumentsCreateBody(DocumentFormat documentFormat, ProductDocument productDocument, ProductGroup productGroup, String signature, DocumentType type) {
            this.documentFormat = documentFormat;
            // Согласно документации, содержимое документа должно быть Base64(JSON.stringify)
            String json = "";
            try {
                json = mapper.writer().writeValueAsString(productDocument);
            } catch (JsonProcessingException ignored) {}
            this.productDocument = Base64.getEncoder().encodeToString(json.getBytes());
            this.productGroup = productGroup;
            this.signature = signature;
            this.type = type;
        }
    }

    /**
     * Документ
     */
    @Getter
    public static class ProductDocument {
        private final Map<String, String> description;
        private final String doc_id;
        private final String doc_status;
        private final String doc_type;
        private final String importRequest;
        private final String owner_inn;
        private final String participant_inn;
        private final String producer_inn;
        private final String production_date;
        private final String production_type;
        private final List<Product> products;
        private final String reg_date;
        private final String reg_number;

        public ProductDocument(String participantInn, String doc_id, String doc_status, String doc_type, boolean importRequest, String owner_inn, String participant_inn, String producer_inn, String production_date, String production_type, List<Product> products, String reg_date, String reg_number) {
            this.description = new HashMap<>(Map.of("participantInn", participantInn));
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.importRequest = String.valueOf(importRequest);
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.production_type = production_type;
            this.products = products;
            this.reg_date = reg_date;
            this.reg_number = reg_number;
        }
    }

    /**
     * Товар
     */
    @Getter
    public static class Product {
        private final CertificateDocument certificate_document;
        private final String certificate_document_date;
        private final String certificate_document_number;
        private final String owner_inn;
        private final String producer_inn;
        private final String production_date;
        private final String tnved_code;
        private final String uit_code;
        private final String uitu_code;

        public Product(CertificateDocument certificate_document, String certificate_document_date, String certificate_document_number, String owner_inn, String producer_inn, String production_date, String tnved_code, String uit_code, String uitu_code) {
            this.certificate_document = certificate_document;
            this.certificate_document_date = certificate_document_date;
            this.certificate_document_number = certificate_document_number;
            this.owner_inn = owner_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.tnved_code = tnved_code;
            this.uit_code = uit_code;
            this.uitu_code = uitu_code;
        }
    }

    /**
     * Метод запроса
     */
    private enum RequestMethod {
        GET,
        POST,
    }

    /**
     * Код вида документа обязательной сертификации
     */
    public enum CertificateDocument {
        CONFORMITY_CERTIFICATE,
        CONFORMITY_DECLARATIO,
    }

    /**
     * Товарная группа
     */
    public enum ProductGroup {
        clothes,
        shoes,
        tobacco,
        perfumery,
        tires,
        electronics,
        pharma,
        milk,
        bicycle,
        wheelchairs,
    }

    /**
     * Формат документа
     */
    private enum DocumentFormat {
        MANUAL,
        XML,
        CSV,
    }

    /**
     * Тип документа
     */
    private enum DocumentType {
        AGGREGATION_DOCUMENT,
        AGGREGATION_DOCUMENT_CSV,
        AGGREGATION_DOCUMENT_XML,
        DISAGGREGATION_DOCUMENT,
        DISAGGREGATION_DOCUMENT_CSV,
        DISAGGREGATION_DOCUMENT_XML,
        REAGGREGATION_DOCUMENT,
        REAGGREGATION_DOCUMENT_CSV,
        REAGGREGATION_DOCUMENT_XML,
        LP_INTRODUCE_GOODS,
        LP_SHIP_GOODS,
        LP_SHIP_GOODS_CSV,
        LP_SHIP_GOODS_XML,
        LP_INTRODUCE_GOODS_CSV,
        LP_INTRODUCE_GOODS_XML,
        LP_ACCEPT_GOODS,
        LP_ACCEPT_GOODS_XML,
        LK_REMARK,
        LK_REMARK_CSV,
        LK_REMARK_XML,
        LK_RECEIPT,
        LK_RECEIPT_XML,
        LK_RECEIPT_CSV,
        LP_GOODS_IMPORT,
        LP_GOODS_IMPORT_CSV,
        LP_GOODS_IMPORT_XML,
        LP_CANCEL_SHIPMENT,
        LP_CANCEL_SHIPMENT_CSV,
        LP_CANCEL_SHIPMENT_XML,
        LK_KM_CANCELLATION,
        LK_KM_CANCELLATION_CSV,
        LK_KM_CANCELLATION_XML,
        LK_APPLIED_KM_CANCELLATION,
        LK_APPLIED_KM_CANCELLATION_CSV,
        LK_APPLIED_KM_CANCELLATION_XML,
        LK_CONTRACT_COMMISSIONING,
        LK_CONTRACT_COMMISSIONING_CSV,
        LK_CONTRACT_COMMISSIONING_XML,
        LK_INDI_COMMISSIONING,
        LK_INDI_COMMISSIONING_CSV,
        LK_INDI_COMMISSIONING_XML,
        LP_SHIP_RECEIPT,
        LP_SHIP_RECEIPT_CSV,
        LP_SHIP_RECEIPT_XML,
        OST_DESCRIPTION,
        OST_DESCRIPTION_CSV,
        OST_DESCRIPTION_XML,
        CROSSBORDER,
        CROSSBORDER_CSV,
        CROSSBORDER_XML,
        LP_INTRODUCE_OST,
        LP_INTRODUCE_OST_CSV,
        LP_INTRODUCE_OST_XML,
        LP_RETURN,
        LP_RETURN_CSV,
        LP_RETURN_XML,
        LP_SHIP_GOODS_CROSSBORDER,
        LP_SHIP_GOODS_CROSSBORDER_CSV,
        LP_SHIP_GOODS_CROSSBORDER_XML,
        LP_CANCEL_SHIPMENT_CROSSBORDER,
    }
}
