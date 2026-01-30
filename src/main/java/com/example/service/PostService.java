package com.example.service;

import com.example.enums.TemplateType;
import com.example.vo.PostDetailVO;
import com.example.vo.PostListVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author lhh
 */
@Service
public interface PostService {
    List<PostListVO> getCategoryPosts(Long userId, Long categoryId);

    PostDetailVO getPostDetail(Long userId, Long id);

    TemplateType downloadTemplateFile(Integer fileId);
}
