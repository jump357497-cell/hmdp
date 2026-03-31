package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询最热的博客
     * @param current 前几篇
     * @return 最热博客
     */
    Result queryHotBlog(Integer current);

    /**
     * 查看博客详情
     * @param id 博客id
     * @return 博客具体内容
     */
    Result queryBlogById(Long id);

    /**
     * 点赞博客
     * @param id 博客id
     * @return
     */
    Result likeBlog(Long id);


    /**
     * 博客点赞排行
     * @param id 博客id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布博客，同时推送博客到粉丝的收件箱
     * @param blog 博客
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 收件箱中博客的推送顺序
     * @param max 上一次查询的最小值（相当于本次查询的最大值）
     * @param offset 跟上一次查询的最小值相同的数（偏移量）
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);

}
