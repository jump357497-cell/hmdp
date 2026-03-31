package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注/取关
     * @param followUserId 关注的用户id
     * @param isFollow 是否关注
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否关注了本篇博客的作者
     * @param followUserId 博客的作者
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 共同关注
     * @param id 他人id
     * @return
     */
    Result followCommons(Long id);

}
