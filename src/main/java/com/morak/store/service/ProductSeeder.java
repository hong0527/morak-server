package com.morak.store.service;

import com.morak.store.entity.Product;
import com.morak.store.repository.ProductRepository;
import com.morak.store.type.ProductType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 상품 시드. <b>실제 상품 목록과 가격은 팀 확정 대기이고 여기 값은 전부 잠정이다</b>
 * (Q7). 확정되면 운영 데이터는 마이그레이션으로 넣고 이 시드는 dev 화면용으로 남는다.
 *
 * <p>상품 등록 API가 v1에 없어(관리자 스토어는 절단선 밖) 시드가 없으면 dev에서 스토어를
 * 열어 볼 방법이 없다.
 *
 * <p>한 벌에 판매중·품절·감춤·재고 1을 모두 넣은 것은 의도다. 스토어 화면이 다루는 상태가
 * 그게 전부라, 하나라도 빠지면 그 상태의 표시를 개발 중에 확인할 수 없다.
 *
 * <p>{@code @Profile("dev")}만 걸고 {@code morak.dev.enabled} 이중 스위치를 두지 않는다.
 * 이중 스위치는 인증 우회처럼 운영에서 열리면 사고가 되는 경로를 잠그는 장치고, 여기는
 * 데이터를 넣을 뿐이다.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class ProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSeeder.class);

    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 상품에는 자연키가 없다(같은 이름의 상품이 둘일 수 있다). 카탈로그를 한 벌로 보고
        // 비어 있을 때만 넣는 편이, 이름으로 짝을 맞춰 부분 삽입하는 것보다 결과가 분명하다.
        if (productRepository.count() > 0) {
            return;
        }
        Product gifticon = Product.onSale(ProductType.GIFTICON, "편의점 5,000원 금액권",
                "전국 편의점에서 사용 가능한 모바일 금액권입니다. 유효기간 발행일로부터 90일.",
                "https://cdn.molock.app/products/1.png", 5500, 42);
        // 이미지 미등록 상품. SR-2의 imageUrl NULL 허용이 화면에서 어떻게 보이는지 확인용이다.
        Product lastOne = Product.onSale(ProductType.GIFTICON, "카페 아메리카노 교환권",
                "제휴 카페에서 사용 가능한 아메리카노 1잔 교환권입니다.", null, 4900, 1);
        Product soldOut = Product.onSale(ProductType.BOOK, "합격을 부르는 공부법",
                "집중 루틴을 다루는 제휴 출판사 도서입니다.",
                "https://cdn.molock.app/products/3.png", 14000, 0);
        soldOut.markSoldOut();
        Product hidden = Product.onSale(ProductType.BOOK, "출간 예정 제휴 도서",
                "공개 준비 중인 상품입니다.", null, 12000, 20);
        hidden.hide();

        List<Product> seeded = List.of(gifticon, lastOne, soldOut, hidden);
        productRepository.saveAll(seeded);
        log.info("개발용 상품 시드 완료 — {}종(가격·재고 잠정)", seeded.size());
    }
}
