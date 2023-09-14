package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;

    /**
     * 点击关注功能
     */
    @PutMapping("/{id}/{isfollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isfollow") Boolean isfollow) {
        return followService.follow(followUserId, isfollow);
    }

    /**
     * 判断当前用户是否关注
     */
    @GetMapping("/or/not/{id}")
    public Result followOrNot(@PathVariable("id") Long followUserId) {
        return followService.followOrNot(followUserId);
    }

    /**
     * 查询共同关注
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
