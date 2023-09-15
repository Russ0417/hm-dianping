package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    /**
     * 分页
     */
    Result queryHotBlog(Integer current);

    /**
     * 博客点赞
     */
    Result likeBlog(Long id);

    /**
     * 博客点赞列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布博客
     */
    Result saveBlog(Blog blog);

    /**
     * 关注用户的博客推送
     *
     * @param max
     * @param offset
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
