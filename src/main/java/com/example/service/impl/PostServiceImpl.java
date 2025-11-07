package com.example.service.impl;

import com.example.mapper.CategoryMapper;
import com.example.mapper.PostMapper;
import com.example.service.PostService;
import com.example.vo.PostDetailVO;
import com.example.vo.PostListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author lhh
 */
@Service
public class PostServiceImpl implements PostService {
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    public List<PostListVO> getCategoryPosts(Long userId, Long categoryId) {
        if (!categoryMapper.existById(categoryId)) throw new RuntimeException("分类不存在");
        return postMapper.getCategoryPosts(categoryId);
    }

    @Override
    public PostDetailVO getPostDetail(Long userId, Long id) {
        PostDetailVO postDetailVO = postMapper.findById(id);
        if (postDetailVO==null) throw new RuntimeException("帖子不存在");
        return postDetailVO;
    }
}
