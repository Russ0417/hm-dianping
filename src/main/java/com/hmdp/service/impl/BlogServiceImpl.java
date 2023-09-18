package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    private IFollowService followService;
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
        Page<Blog> page = this.query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
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
        List<UserDTO> userDTOS = userService.query().in("id", idList).last("ORDER BY FIELD(id," + idstr + ")").list().
                stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 发布博客，同时存入Redis的ZSet队列中(用于推送给关注的用户)
     */
    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean result = save(blog);
        if (!result) {
            return Result.fail("新增失败");
        }
        //查询笔记作者的粉丝列表
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //将当前时间戳作为存入ZSet的score,从而实现排序
        long time = System.currentTimeMillis();
        //推送消息
        for (Follow follow : follows) {
            Long fansId = follow.getUserId();
            String key = FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), time);
        }
        return Result.ok(blog.getId());
    }

    /**
     * 滚动博客列表查询
     *
     * @param max    一次请求博客个数
     * @param offset 偏移量
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();

        //1.查询收件箱所有博客
        String key = FEED_KEY + userId;
        //这里的count代表单次请求的博客个数
        long count = 2;
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, count);
        //非空判断
        if (typedTuples.isEmpty() || typedTuples == null) {
            return Result.fail("没有最新的消息更新！");
        }

        //2.开始解析Redis中Zset的数据
        //创建idList存储id，大小和set相同
        List<Long> idList = new ArrayList<>(typedTuples.size());
        //默认时间为0,偏移量为1（至少存在1个最小值，至少需要偏移1）
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            idList.add(Long.valueOf(typedTuple.getValue()));
            //获取当前列表数据的最小值的分数，即最小的时间戳
            long time = typedTuple.getScore().longValue();
            if (os == time) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        //3.根据id查博客信息
        String idstr = StrUtil.join(",", idList);
        List<Blog> blogs = query().in("id", idList).last("ORDER BY FIELD(id," + idstr + ")").list();
        //循环遍历 添加blog信息
        for (Blog blog : blogs) {
            //查询blog相关用户
            queryBlogUser(blog);
            //查询当前博客是否被点赞
            isBlogLiked(blog);
        }
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(os);
        result.setMinTime(minTime);

        //4.封装返回
        return Result.ok(result);
    }


    /**
     * 查询当前博客是否被点赞
     */
    private void isBlogLiked(Blog blog) {
        String key = BLOG_LIKED_KEY + blog.getId().toString();
        UserDTO user = UserHolder.getUser();
        if (BeanUtil.isEmpty(user)) {
            return; // 用户未登录
        }
        Long userId = user.getId();
        //查询博客的点赞队列中是否存在当前用户
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 查询博客相关用户
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
