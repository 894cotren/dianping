package com.hmdp.service.impl;

import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ShopServiceImplTest {

    @Autowired
    private IShopService iShopService;


    @Test
    void saveShop2Redis() {
        iShopService.saveShop2Redis(1l,20l);
    }
}
