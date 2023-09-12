package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 分页
     *
     * @param current
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            //查询博客相关用户
            this.queryBlogUser(blog);
            //查询博客是否被点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询博客信息
     *
     * @param id
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        //查询blog相关用户
        queryBlogUser(blog);
        //查询当前博客是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询当前博客是否被点赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        String key = BLOG_LIKED_KEY + blog.getId().toString();
        UserDTO user = UserHolder.getUser();
        if (BeanUtil.isEmpty(user)) {
            return; // 用户未登录
        }
        Long userId = user.getId();
        //查询博客的点赞队列中是否存在当前用户
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        blog.setIsLike(score != null);
    }

    /**
     * 查询博客相关用户
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 博客点赞
     */
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("请登录后再点赞");
        }
        String key = BLOG_LIKED_KEY + id;
        //判断用户是否点过赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //不存在，没点过赞
            boolean issuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (issuccess) {
                //更新成功，则更新redis的ZSet集合 使用时间戳作为score，实现点赞用户的排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //存在 点过赞
            boolean issuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (issuccess) {
                //更新成功，则更新redis的ZSet集合
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        Blog blog = this.getById(id);
        return Result.ok();
    }

    /**
     * 博客点赞列表
     *
     * @param id
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> stringSet = stringRedisTemplate.opsForZSet().range(key, 0, 4);//查询前五条数据
        if (stringSet == null || stringSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> idList = stringSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", idList);
        List<UserDTO> userDTOS = userService.query()
                .in("id", idList)
                .last("ORDER BY FIELD(id," + idstr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
