package com.hewei.hzyjy.xunzhi.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ServiceException;
import com.hewei.hzyjy.xunzhi.common.enums.UserErrorCodeEnum;
import com.hewei.hzyjy.xunzhi.user.dao.entity.UserDO;
import com.hewei.hzyjy.xunzhi.user.dao.mapper.UserMapper;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserLoginReqDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserPageReqDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserRegisterReqDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserUpdateReqDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.resp.UserLoginRespDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.resp.UserPageRespDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.resp.UserRespDTO;
import com.hewei.hzyjy.xunzhi.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hewei.hzyjy.xunzhi.common.constant.RedisCacheConstant.LOCK_USER_REGISTER_KEY;
import static com.hewei.hzyjy.xunzhi.common.constant.RedisCacheConstant.USER_LOGIN_KEY;
import static com.hewei.hzyjy.xunzhi.common.enums.UserErrorCodeEnum.USER_EXIST;
import static com.hewei.hzyjy.xunzhi.common.enums.UserErrorCodeEnum.USER_NAME_EXIST;
import static com.hewei.hzyjy.xunzhi.common.enums.UserErrorCodeEnum.USER_SAVE_ERROR;
import cn.dev33.satoken.stp.StpUtil;
import com.hewei.hzyjy.xunzhi.auth.application.LoginSessionService;

/**
 * User service implementation.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    //private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    // private final RedissonClient redissonClient;
    //private final StringRedisTemplate stringRedisTemplate;

    @Override
    public UserRespDTO getUserByUsername(String username) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        UserRespDTO result = new UserRespDTO();
        BeanUtils.copyProperties(userDO, result);
        return result;
    }

    @Override
    public Boolean hasUsername(String username) {
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void register(UserRegisterReqDTO requestParam) {
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(UserErrorCodeEnum.USER_EXIST);
        }
        // RLock lock = redissonClient.getLock("user:register:" + requestParam.getUsername());
        // if (!lock.tryLock()) {
        //     throw new ClientException(UserErrorCodeEnum.REGISTER_REPEAT);
        // }
        try {
            int inserted = baseMapper.insert(BeanUtil.toBean(requestParam, UserDO.class));
            if (inserted < 1) {
                throw new ClientException(UserErrorCodeEnum.USER_SAVE_ERROR);
            }
            // userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
        } catch (DuplicateKeyException ex) {
            throw new ClientException(UserErrorCodeEnum.USER_EXIST);
        }
        // finally {
        //     lock.unlock();
        // }
    }

    @Override
    public void update(UserUpdateReqDTO requestParam, String currentUsername) {
        if (requestParam == null) {
            throw new ClientException("request body cannot be empty");
        }
        if (StrUtil.isBlank(currentUsername)) {
            throw new ClientException("current user is not logged in");
        }
        if (StrUtil.isNotBlank(requestParam.getUsername()) && !currentUsername.equals(requestParam.getUsername())) {
            throw new ClientException("no permission to update other users");
        }

        requestParam.setUsername(currentUsername);
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUsername, currentUsername);
        baseMapper.update(BeanUtil.toBean(requestParam, UserDO.class), updateWrapper);
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        if (!requestParam.getPassword().equals(userDO.getPassword())) {
            throw new ClientException("密码错误");
        }
        StpUtil.login(userDO.getId());
        String token = StpUtil.getTokenValue();
        UserLoginRespDTO resp = new UserLoginRespDTO();
        resp.setToken(token);
        // 删掉 resp.setUserId 这一行！
        return resp;
    }



    @Override
    public Boolean checkLogin(String username, String token) {
        try {
            StpUtil.setTokenValue(token);
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void logout(String username, String token) {
        StpUtil.setTokenValue(token);
        StpUtil.logout();
    }

    @Override
    public IPage<UserPageRespDTO> pageUsers(UserPageReqDTO requestParam) {
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getDelFlag, 0);

        if (requestParam.getKeyword() != null && !requestParam.getKeyword().trim().isEmpty()) {
            String keyword = requestParam.getKeyword().trim();
            queryWrapper.and(wrapper -> wrapper
                    .like(UserDO::getUsername, keyword)
                    .or().like(UserDO::getRealName, keyword)
                    .or().like(UserDO::getMail, keyword)
            );
        }

        if (requestParam.getCreateTimeSort() != null) {
            if ("asc".equalsIgnoreCase(requestParam.getCreateTimeSort())) {
                queryWrapper.orderByAsc(UserDO::getCreateTime);
            } else {
                queryWrapper.orderByDesc(UserDO::getCreateTime);
            }
        } else {
            queryWrapper.orderByDesc(UserDO::getCreateTime);
        }

        IPage<UserDO> userPage = baseMapper.selectPage(page, queryWrapper);

        Page<UserPageRespDTO> resultPage = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        resultPage.setTotal(userPage.getTotal());

        if (CollUtil.isNotEmpty(userPage.getRecords())) {
            resultPage.setRecords(userPage.getRecords().stream()
                    .map(userDO -> {
                        UserPageRespDTO respDTO = new UserPageRespDTO();
                        BeanUtils.copyProperties(userDO, respDTO);
                        return respDTO;
                    })
                    .toList());
        }
        return resultPage;
    }
}

