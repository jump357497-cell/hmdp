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
import com.hmdp.utils.RedisConstants;
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

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
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
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }
    //查询blog相关用户信息 ctrl+alt+m 选定代码快捷生成函数
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询Blog
        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("笔记不存在！");
        }
        //2.查询用户信息并赋值
        queryBlogUser(blog);
        //3.查询blog是否被点赞
        isBlogLiked(blog);
        //4.返回笔记
        return Result.ok(blog);
    }

    //查询当前用户点赞的博客
    private void isBlogLiked(Blog blog){
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        //2.如果用户未登录，则返回
        if (user==null) return;
        //3.获取用户id
        Long userId = user.getId();
        //4.判断当前登录用户是否已经点赞
        String key=RedisConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }



    @Override
    public Result likeBlog(Long id) {
        //1.获取登录的用户id(用作set的value值)
        Long userId = UserHolder.getUser().getId();
        //2.判断用户是否点赞
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        //查的对应的score不存在则说明用户未点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score==null){
            //3.如果未点赞
            //3.1数据库点赞+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2把用户放入redis中的sorted set集合,score存放当前时间
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果已经点赞，取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            //4.2把用户从Redis的sorted set集合移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        //1.查询top 5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null||top5.isEmpty()){
            //说明没人点赞，返回空数组
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids=top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //我们查询出来的用户如何按照id列表的顺序排序，就需要我们自定义排序规则ORDER BY FIELD
        //3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOs = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id,"+idStr+")").list()
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOs);
    }


    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2。保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //3.查询笔者的所有粉丝信息 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2 推送
            String key= FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId=UserHolder.getUser().getId();
        //2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据：blogId、minTime(时间戳)、offset
        List<Long> ids=new ArrayList<>(typedTuples.size());//这里直接指定list的大小
        long minTime=0;//记录最小时间
        int os=1;//记录与最小相同的值
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2获取分数（时间戳）
            long time=tuple.getScore().longValue();
            if (time==minTime){
                os++;
            }else {
                minTime=time;//更新最小值
                os=1;//重新置为1 因为我们要记录的是跟最小值相同的 中间相同不需要统计
            }

        }

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        //我们需要自定义排序规则，使id按照我们传入的顺序查找
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //需要更新完整我们的博客信息
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }



}
