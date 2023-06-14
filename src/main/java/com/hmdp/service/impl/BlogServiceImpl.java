package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    @Override
    public Result queryById(int id) {
        //先查笔记
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("未找到该博客");
        }
        //查询用户
        Long userId = blog.getUserId();

        User user = userService.getById(userId);
        blog.setUserId(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        isLiked(blog);

        return Result.ok(blog);
    }

    public void isLiked(Blog blog) {
        //从redis中查询该用户是否点过赞
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return;
        }
        //获取 登录用户
        Long UserId = UserHolder.getUser().getId();
        //判断是否点赞
        String Key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(Key, UserId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取userId
        Long userId = UserHolder.getUser().getId();
        //判断该用户是否在此博客点过藏
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());
        if (score == null) {
            //证明该用户未给该博客点赞
            //数据进行liked字段加一并将该用户id存到redis中去
            boolean updateSuccess = this.update().setSql("liked=liked+1").eq("id", id).update();
            if (updateSuccess == true) {
//                该用户id存到redis中去
                stringRedisTemplate.opsForZSet().add("blog:liked:" + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean updateSuccess = this.update().setSql("liked=liked-1").eq("id", id).update();
            if (updateSuccess == true) {
//                该用户id从redis移除
                stringRedisTemplate.opsForZSet().remove("blog:liked:" + id, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result likesBlog(Long id) {
        String Key = "blog:liked:" + id;

        Set<String> top5 = stringRedisTemplate.opsForZSet().range(Key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //将ids解析为Long型
        List<Long> userIdS = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String joinIds = StrUtil.join(",", userIdS);
        //解决in 查询时结果和输入时顺序不同
        List<User> users = userService.query().in("id", userIdS).last("ORDER BY FIELD(id," + joinIds + ")").list();
        //将user转userDto返回
        List<UserDTO> userDTOS = users.stream().map(item -> {
            UserDTO userDTO = BeanUtil.copyProperties(item, UserDTO.class);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("添加笔记失败");
        }

        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowUserId, user.getId());
        List<Follow> followList = followService.list(queryWrapper);
        for (Follow follow : followList) {
            Long userId = follow.getUserId();
            //推送到每个粉丝的收件箱中去
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result guanZhu(Long max, Long offset) {
        //获取用户id 根据key去进行滚动分页查询
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;

        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        List<Long> blogIdList = new ArrayList<>(typedTuples.size());

        long minTineMinter = 0;
        //重复值计数
        int a = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //value值
            String blogId = tuple.getValue();
            blogIdList.add(Long.valueOf(blogId));
            //分数 时间戳
            long timeMinter = tuple.getScore().longValue();
            if (minTineMinter == timeMinter) {
                a++;
            } else {
                minTineMinter = timeMinter;
                a = 1;
            }
        }
        if (blogIdList==null||blogIdList.isEmpty()){
            return Result.ok();
        }
        String str = StrUtil.join(",", blogIdList);
        List<Blog> blogs = query().in("id",blogIdList).last("ORDER BY FIELD(id,"+str+")").list();
        for (Blog blog : blogs) {
            //判断是否被点过赞
            //获取 登录用户
            Long UserId = UserHolder.getUser().getId();
            //判断是否点赞
            String Key = "blog:liked:" + blog.getId();
            Double score = stringRedisTemplate.opsForZSet().score(Key, UserId.toString());
            blog.setIsLike(score != null);
        }
        //封装返回结果
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTineMinter);
        r.setOffset(a);

        return Result.ok(r);
    }
}
