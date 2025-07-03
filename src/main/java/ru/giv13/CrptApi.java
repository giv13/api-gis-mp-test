package ru.giv13;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private static final int tokenLifetime = 10 * 60 * 60 * 1000; // 10 часов (согласно документации) в миллисекундах
    private volatile long tokenExpiresAt;
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
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }
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
        Map<String, String> uriParams = new HashMap<>(Map.of("pg", body.getProductGroup()));
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
                if ((token == null || System.currentTimeMillis() > tokenExpiresAt) && !authFailed) {
                    synchronized (this) {
                        if ((token == null || System.currentTimeMillis() > tokenExpiresAt) && !authFailed) {
                            token = authCert(authCertKey());
                            if (token == null) {
                                authFailed = true;
                                throw new Exception("Failed to get token");
                            } else {
                                tokenExpiresAt = System.currentTimeMillis() + tokenLifetime;
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
                String json = body instanceof String ? (String) body : mapper.writeValueAsString(body);
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
        @JsonProperty("document_format")
        private final String documentFormat;
        @JsonProperty("product_document")
        private final String productDocument;
        @JsonProperty("product_group")
        private final String productGroup;
        private final String signature;
        private final String type;

        public LkDocumentsCreateBody(DocumentFormat documentFormat, ProductDocument productDocument, ProductGroup productGroup, String signature, DocumentType type) {
            this.documentFormat = documentFormat.toString();
            // Согласно документации, содержимое документа должно быть Base64(JSON.stringify)
            String json = "";
            try {
                json = mapper.writeValueAsString(productDocument);
            } catch (JsonProcessingException ignored) {}
            this.productDocument = Base64.getEncoder().encodeToString(json.getBytes());
            this.productGroup = productGroup.toString();
            this.signature = signature;
            this.type = type.toString();
        }
    }

    /**
     * Документ
     */
    @Getter
    public static class ProductDocument {
        private final Map<String, String> description;
        @JsonProperty("doc_id")
        private final String docId;
        @JsonProperty("doc_status")
        private final String docStatus;
        @JsonProperty("doc_type")
        private final String docType;
        private final String importRequest;
        @JsonProperty("owner_inn")
        private final String ownerInn;
        @JsonProperty("participant_inn")
        private final String participantInn;
        @JsonProperty("producer_inn")
        private final String producerInn;
        @JsonProperty("production_date")
        private final String productionDate;
        @JsonProperty("production_type")
        private final String productionType;
        private final List<Product> products;
        @JsonProperty("reg_date")
        private final String regDate;
        @JsonProperty("reg_number")
        private final String regNumber;

        public ProductDocument(String docId, String docStatus, String docType, boolean importRequest, String ownerInn, String participantInn, String producerInn, String productionDate, String productionType, List<Product> products, String regDate, String regNumber) {
            this.description = new HashMap<>(Map.of("participantInn", participantInn));
            this.docId = docId;
            this.docStatus = docStatus;
            this.docType = docType;
            this.importRequest = String.valueOf(importRequest);
            this.ownerInn = ownerInn;
            this.participantInn = participantInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.productionType = productionType;
            this.products = products;
            this.regDate = regDate;
            this.regNumber = regNumber;
        }
    }

    /**
     * Товар
     */
    @Getter
    public static class Product {
        @JsonProperty("certificate_document")
        private final CertificateDocument certificateDocument;
        @JsonProperty("certificate_document_date")
        private final String certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        private final String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        private final String ownerInn;
        @JsonProperty("producer_inn")
        private final String producerInn;
        @JsonProperty("production_date")
        private final String productionDate;
        @JsonProperty("tnved_code")
        private final String tnvedCode;
        @JsonProperty("uit_code")
        private final String uitCode;
        @JsonProperty("uitu_code")
        private final String uituCode;

        public Product(CertificateDocument certificateDocument, String certificateDocumentDate, String certificateDocumentNumber, String ownerInn, String producerInn, String productionDate, String tnvedCode, String uitCode, String uituCode) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.ownerInn = ownerInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.tnvedCode = tnvedCode;
            this.uitCode = uitCode;
            this.uituCode = uituCode;
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
