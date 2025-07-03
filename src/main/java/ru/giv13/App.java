package ru.giv13;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class App
{
    public static void main( String[] args )
    {
        try (
                CrptApi crptApi = CrptApi.getInstance("https://ismp.crpt.ru/api/v3", TimeUnit.SECONDS, 10);
                ExecutorService executor = Executors.newFixedThreadPool(5)
        ) {
            for (int i = 0; i < 20; i++) {
                executor.submit(() -> {
                    try {
                        List<CrptApi.Product> products = new ArrayList<>();
                        products.add(new CrptApi.Product(
                                CrptApi.CertificateDocument.CONFORMITY_CERTIFICATE,
                                "2025-06-01",
                                "1111111111",
                                "1111111111",
                                "1111111111",
                                "2025-06-01",
                                "1111111111",
                                "1111111111",
                                "1111111111"
                        ));
                        CrptApi.ProductDocument productDocument = new CrptApi.ProductDocument(
                                "1111111111",
                                "1111111111",
                                "success",
                                "OWN_PRODUCTION",
                                true,
                                "1111111111",
                                "1111111111",
                                "1111111111",
                                "2025-06-01",
                                "OWN_PRODUCTION",
                                products,
                                "2025-06-01",
                                "1111111111"
                        );
                        String productUUID = crptApi.lkDocumentsCreateLpIntroduceGoods(CrptApi.ProductGroup.milk, productDocument, "Some signature");
                        System.out.println("Thread " + Thread.currentThread().getName() + ": " + productUUID);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
}
