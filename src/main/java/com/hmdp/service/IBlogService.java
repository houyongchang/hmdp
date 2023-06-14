package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IBlogService extends IService<Blog> {

    Result queryById(int id);

    Result likeBlog(Long id);

    Result likesBlog(Long id);

    Result saveBlog(Blog blog);

    Result guanZhu(Long max, Long offset);
}
