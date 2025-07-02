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
                                "2019-04-12",
                                "1111111111",
                                "1111111111",
                                "1111111111",
                                "2019-04-12",
                                "6401000000",
                                "11111111111111111111111111111111111111",
                                "000000000000000000"
                        ));
                        CrptApi.ProductDocument productDocument = new CrptApi.ProductDocument(
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                products,
                                "",
                                ""
                        );
                        String productUUID = crptApi.lkDocumentsCreateLpIntroduceGoods(CrptApi.ProductGroup.milk, productDocument, "Some signature");
                        System.out.println("Thread " + Thread.currentThread().getName() + ": " + productUUID);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
