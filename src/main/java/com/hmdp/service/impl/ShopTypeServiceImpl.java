package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        List<String> ShopTypeListStr = stringRedisTemplate.opsForList().range("shopType:cache", 0, -1);
        //在redis中存在
        List<ShopType> shopTypeList=new ArrayList<>();
        if (ShopTypeListStr.size()!=0 && ShopTypeListStr!=null){
            for (String ShopType : ShopTypeListStr) {
                com.hmdp.entity.ShopType shopType = JSONUtil.toBean(ShopType, com.hmdp.entity.ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }
        //不存在
        LambdaQueryWrapper<ShopType> queryWrapper=new LambdaQueryWrapper<>();
        queryWrapper.orderByDesc(ShopType::getSort);
        List<ShopType> shopTypes = this.list(queryWrapper);
        if (shopTypes.size()==0&&shopTypes==null){
            return Result.fail("未找到");
        }
        //将数据存到Redis中一份
        for (ShopType shopType : shopTypes) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForList().leftPushAll("shopType:cache",jsonStr);
        }

        return Result.ok(shopTypes);
    }
}
