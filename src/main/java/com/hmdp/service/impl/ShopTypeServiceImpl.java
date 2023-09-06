package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IShopTypeService shopTypeService;

    @Override
    public List<ShopType> queryTypeList() {
        //查看redis是否命中
        List<String> list = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (list.size() > 0 && !list.isEmpty()) {
            List<ShopType> shopTypesList = list.stream()
                    .map(shopType -> JSONUtil.toBean(shopType, ShopType.class))
                    .sorted(Comparator.comparing(ShopType::getId))
                    .collect(Collectors.toList());
            return shopTypesList;
        }
        //未命中则查询数据库，查询数据必定存在
        List<ShopType> shopTypes = shopTypeService.query().orderByAsc("id").list();
        List<String> stringList = shopTypes.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        //查询后再存入Redis中
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, stringList);
        return shopTypes;
    }
}
