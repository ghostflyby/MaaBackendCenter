package plus.maa.backend.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import plus.maa.backend.common.utils.converter.CopilotConverter;
import plus.maa.backend.controller.request.CopilotCUDRequest;
import plus.maa.backend.controller.request.CopilotDTO;
import plus.maa.backend.controller.request.CopilotQueriesRequest;
import plus.maa.backend.controller.request.CopilotRatingReq;
import plus.maa.backend.controller.response.*;
import plus.maa.backend.repository.CopilotRatingRepository;
import plus.maa.backend.repository.CopilotRepository;
import plus.maa.backend.repository.RedisCache;
import plus.maa.backend.repository.TableLogicDelete;
import plus.maa.backend.repository.entity.Copilot;
import plus.maa.backend.repository.entity.CopilotRating;
import plus.maa.backend.service.model.LoginUser;
import plus.maa.backend.service.model.RatingType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;


/**
 * @author LoMu
 * Date  2022-12-25 19:57
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotService {
    private final CopilotRepository copilotRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper mapper;
    private final ArkLevelService levelService;

    private final HttpServletRequest request;

    private final RedisCache redisCache;

    private final TableLogicDelete tableLogicDelete;

    private final CopilotRatingRepository copilotRatingRepository;


    /**
     * 根据_id获取Copilot
     *
     * @param id _id
     * @return Copilot
     */
    private Copilot findById(String id) {
        Optional<Copilot> optional = copilotRepository.findById(id);

        Copilot copilot;
        if (optional.isPresent()) {
            copilot = optional.get();
        } else {
            throw new MaaResultException("作业id不存在");
        }
        return copilot;
    }

    /**
     * 验证当前账户是否为作业创建者
     *
     * @param operationId 作业id
     * @return boolean
     */
    private Boolean verifyOwner(LoginUser user, String operationId) {
        if (operationId == null) {
            throw new MaaResultException("作业id不可为空");
        }

        String userId = user.getMaaUser().getUserId();
        Copilot copilot = findById(operationId);
        return Objects.equals(copilot.getUploaderId(), userId);
    }

    /**
     * 验证数值是否合法
     * 并修正前端的冗余部分
     *
     * @param copilotDTO copilotDTO
     */
    private CopilotDTO CorrectCopilot(CopilotDTO copilotDTO) {

        //去除name的冗余部分
        copilotDTO.getGroups().forEach(groups -> groups.getOpers().forEach(opers -> opers.setName(opers.getName().replaceAll("[\"“”]", ""))));
        copilotDTO.getOpers().forEach(operator -> operator.setName(operator.getName().replaceAll("[\"“”]", "")));
        copilotDTO.getActions().forEach(action -> action.setName(action.getName().replaceAll("[\"“”]", "")));
        return copilotDTO;
    }


    /**
     * 将content解析为CopilotDTO
     *
     * @param content content
     * @return CopilotDTO
     */
    private CopilotDTO parseToCopilotDto(String content) {
        if (content == null) {
            throw new MaaResultException("数据不可为空");
        }
        CopilotDTO copilotDto;
        try {
            copilotDto = mapper.readValue(content, CopilotDTO.class);
        } catch (JsonProcessingException e) {
            log.error("解析copilot失败", e);
            throw new MaaResultException("解析copilot失败");
        }
        return copilotDto;
    }

    /**
     * 上传新的作业
     *
     * @param content 前端编辑json作业内容
     * @return 返回_id
     */
    public MaaResult<String> upload(LoginUser user, String content) {
        CopilotDTO copilotDTO = CorrectCopilot(parseToCopilotDto(content));
        Date date = new Date();

        //将其转换为数据库存储对象
        Copilot copilot = CopilotConverter.INSTANCE.toCopilot(copilotDTO);
        copilot.setUploaderId(user.getMaaUser().getUserId())
                .setUploader(user.getMaaUser().getUserName())
                .setFirstUploadTime(date)
                .setUploadTime(date);
        CopilotRating copilotRating = new CopilotRating();

        try {
            String id = copilotRepository.insert(copilot).getId();
            copilotRating.setCopilotId(id);
            copilotRatingRepository.insert(copilotRating);
            return MaaResult.success(id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 删除指定_id
     *
     * @param request _id
     * @return null
     */
    public MaaResult<Void> delete(LoginUser user, CopilotCUDRequest request) {
        String operationId = request.getId();

        if (verifyOwner(user, operationId)) {
            tableLogicDelete.deleteCopilotById(operationId);

            return MaaResult.success(null);
        } else {
            throw new MaaResultException("无法删除他人作业");
        }

    }

    /**
     * 指定查询
     *
     * @param id copilot _id
     * @return copilotInfo
     */
    public MaaResult<CopilotInfo> getCopilotById(LoginUser user, String id) {
        String userId = getUserId(user);

        //限views
        if (redisCache.getCache("views:" + userId, String.class) == null) {
            Query query = Query.query(Criteria.where("id").is(id).and("delete").is(false));
            Update update = new Update();
            //增加一次views
            update.inc("views");
            mongoTemplate.updateFirst(query, update, Copilot.class);
            redisCache.setCache("views:" + userId, "1", 60, TimeUnit.MINUTES);
        }
        Copilot copilot = findById(id);
        CopilotInfo info = formatCopilot(userId, copilot);

        return MaaResult.success(info);
    }


    /**
     * 分页查询
     *
     * @param user    获取已登录用户自己的作业数据
     * @param request 模糊查询
     * @return CopilotPageInfo
     */

    //如果是查最新数据或指定搜索 就不缓存
    @Cacheable(value = "copilotPage",
            condition = "#request.levelKeyword != null && ''.equals(#request.levelKeyword) " +
                    "|| #request.operator != null && ''.equals(#request.operator)" +
                    "|| #request.orderBy != null && ('hot'.equals(#request.orderBy) || 'views'.equals(#request.orderBy)) " +
                    "|| #request.uploaderId != null && ''.equals(#request.uploaderId)")
    public MaaResult<CopilotPageInfo> queriesCopilot(LoginUser user, CopilotQueriesRequest request) {
        String userId = getUserId(user);
        String orderBy = "id";
        Sort.Order sortOrder = new Sort.Order(Sort.Direction.ASC, orderBy);
        int page = 1;
        int limit = 10;
        boolean hasNext = false;

        //判断是否有值 无值则为默认
        if (request.getPage() > 0) {
            page = request.getPage();
        }
        if (request.getLimit() > 0) {
            limit = request.getLimit();
        }
        if (StringUtils.isNotBlank(request.getOrderBy())) {
            if ("hot".equals(request.getOrderBy())) {
                orderBy = "hotScore";
            } else {
                orderBy = request.getOrderBy();
            }
        }
        if (request.isDesc()) {
            sortOrder = new Sort.Order(Sort.Direction.DESC, orderBy);
        }

        Pageable pageable = PageRequest.of(
                page - 1, limit
                , Sort.by(sortOrder));


        //模糊查询
        Query queryObj = new Query();
        Criteria criteriaObj = new Criteria();
        Set<Criteria> andQueries = new HashSet<>();
        Set<Criteria> norQueries = new HashSet<>();
        Set<Criteria> orQueries = new HashSet<>();
        andQueries.add(Criteria.where("delete").is(false));

        //匹配模糊查询
        if (StringUtils.isNotBlank(request.getLevelKeyword())) {
            ArkLevelInfo levelInfo = levelService.queryLevel(request.getLevelKeyword());
            if (levelInfo != null) {
                andQueries.add(Criteria.where("stageName").regex(levelInfo.getStageId()));
            }
        }
        //or模糊查询
        if (StringUtils.isNotBlank(request.getDocument())) {
            orQueries.add(Criteria.where("doc.title").regex(request.getDocument()));
            orQueries.add(Criteria.where("doc.details").regex(request.getDocument()));
        }

        //operator 包含或排除干员查询
        //排除~开头的 查询非~开头
        String oper = request.getOperator();
        if (!ObjectUtils.isEmpty(oper)) {
            oper = oper.replaceAll("[“\"”]", "");
            String[] operators = oper.split(",");
            for (String operator : operators) {
                if ("~".equals(operator.substring(0, 1))) {
                    String exclude = operator.substring(1);
                    //排除查询指定干员
                    norQueries.add(Criteria.where("opers.name").regex(exclude));
                } else {
                    //模糊匹配查询指定干员
                    andQueries.add(Criteria.where("opers.name").regex(operator));
                }
            }
        }

        //匹配查询
        if (StringUtils.isNotBlank(request.getUploaderId()) && "me".equals(request.getUploaderId())) {
            String Id = user.getMaaUser().getUserId();
            if (!ObjectUtils.isEmpty(Id))
                andQueries.add(Criteria.where("uploader").is(Id));
        }

        //封装查询
        if (andQueries.size() > 0) criteriaObj.andOperator(andQueries);
        if (norQueries.size() > 0) criteriaObj.norOperator(norQueries);
        if (orQueries.size() > 0) criteriaObj.orOperator(orQueries);
        queryObj.addCriteria(criteriaObj);

        //查询总数
        long count = mongoTemplate.count(queryObj, Copilot.class);

        //分页排序查询
        List<Copilot> copilots = mongoTemplate.find(queryObj.with(pageable), Copilot.class);
        //填充前端所需信息
        List<CopilotInfo> infos = copilots.stream().map(copilot -> formatCopilot(userId, copilot)).toList();

        //计算页面
        int pageNumber = (int) Math.ceil((double) count / limit);

        //判断是否存在下一页
        if (count - (long) page * limit > 0) {
            hasNext = true;
        }

        //封装数据
        CopilotPageInfo copilotPageInfo = new CopilotPageInfo();
        copilotPageInfo.setTotal(count)
                .setHasNext(hasNext)
                .setData(infos)
                .setPage(pageNumber);
        return MaaResult.success(copilotPageInfo);
    }

    /**
     * 增量更新
     *
     * @param copilotCUDRequest 作业_id  content
     * @return null
     */
    public MaaResult<Void> update(LoginUser loginUser, CopilotCUDRequest copilotCUDRequest) {
        String content = copilotCUDRequest.getContent();
        String id = copilotCUDRequest.getId();
        CopilotDTO copilotDTO = CorrectCopilot(parseToCopilotDto(content));
        Boolean owner = verifyOwner(loginUser, id);

        if (owner) {
            Copilot rawCopilot = findById(id);
            rawCopilot.setUploadTime(new Date());
            CopilotConverter.INSTANCE.updateCopilotFromDto(copilotDTO, rawCopilot);
            copilotRepository.save(rawCopilot);
            return MaaResult.success(null);
        } else {
            throw new MaaResultException("无法更新他人作业");
        }
    }


    /**
     * 评分相关
     *
     * @param request   评分
     * @param loginUser 用于已登录用户作出评分
     * @return null
     */
    public MaaResult<String> rates(LoginUser loginUser, CopilotRatingReq request) {
        //不为空
        if (StringUtils.isBlank(request.getRating())) throw new MaaResultException("rating cannot be is null");

        String userId = getUserId(loginUser);
        String rating = request.getRating();
        //获取评分表
        Query query = Query.query(Criteria.where("copilotId").is(request.getId()));
        Update update = new Update();

        //查询指定作业评分
        CopilotRating copilotRating = mongoTemplate.findOne(query, CopilotRating.class);
        if (copilotRating == null) throw new MaaResultException("server error: Rating is null");

        boolean existUserId = false;
        //点赞数
        int likeCount = 0;
        List<CopilotRating.RatingUser> ratingUsers = copilotRating.getRatingUsers();

        //查看是否已评分 如果已评分则进行更新
        for (CopilotRating.RatingUser ratingUser : ratingUsers) {
            if (userId.equals(ratingUser.getUserId())) {
                existUserId = true;
                ratingUser.setRating(rating);

            }
            if ("Like".equals(ratingUser.getRating())) likeCount++;
        }
        if ("Like".equals(rating)) likeCount++;


        //不存在评分 则添加新的评分
        CopilotRating.RatingUser ratingUser;
        if (!existUserId) {
            ratingUser = new CopilotRating.RatingUser(userId, rating);
            ratingUsers.add(ratingUser);
            update.addToSet("ratingUsers", ratingUser);
            mongoTemplate.updateFirst(query, update, CopilotRating.class);
        }


        //计算评分相关
        int ratingCount = copilotRating.getRatingUsers().size();
        double rawRatingLevel = (double) likeCount / ratingCount;
        BigDecimal bigDecimal = new BigDecimal(rawRatingLevel);
        double ratingLevel = bigDecimal.setScale(1, RoundingMode.HALF_UP).doubleValue();

        //更新数据
        copilotRating.setRatingUsers(ratingUsers);
        copilotRating.setRatingLevel((int) (ratingLevel * 10));
        copilotRating.setRatingRatio(ratingLevel);
        mongoTemplate.save(copilotRating);
        //暂时
        double hotScore = rawRatingLevel + likeCount;
        //更新热度
        if (copilotRepository.findById(request.getId()).isPresent()) {
            Copilot copilot = copilotRepository.findById(request.getId()).get();
            copilot.setHotScore(hotScore);
            mongoTemplate.save(copilot);
        }

        return MaaResult.success("评分成功");
    }


    /**
     * 将数据库内容转换为前端所需格式<br>
     * TODO 当前仅为简单转换，具体细节待定
     */
    private CopilotInfo formatCopilot(String userId, Copilot copilot) {
        CopilotInfo info = CopilotConverter.INSTANCE.toCopilotInfo(copilot);
        //设置干员信息
        List<String> operStrList = copilot.getOpers().stream()
                .map(o -> String.format("%s::%s", o.getName(), o.getSill()))
                .toList();

        //设置干员组干员信息
        if (copilot.getGroups() != null) {
            List<String> operators = new ArrayList<>();
            for (Copilot.Groups group : copilot.getGroups()) {
                if (group.getOpers() != null) {
                    for (Copilot.OperationGroup oper : group.getOpers()) {
                        String format = String.format("%s::%s", oper.getName(), oper.getSill());
                        operators.add(format);
                    }
                }
                group.setOperators(operators);
            }
        }

        info.setOpers(operStrList);
        info.setOperators(operStrList);

        ArkLevelInfo levelInfo = levelService.findByLevelId(copilot.getStageName());
        CopilotRating copilotRating = copilotRatingRepository.findByCopilotId(copilot.getId());
        //判断评分中是否有当前用户评分记录 有则获取其评分并将其转换为  0 = None 1 = LIKE  2 = DISLIKE
        for (CopilotRating.RatingUser ratingUser : copilotRating.getRatingUsers()) {
            if (Objects.equals(userId, ratingUser.getUserId())) {
                int rating = RatingType.fromRatingType(ratingUser.getRating()).getDisplay();
                info.setRatingType(rating);
                break;
            }
        }

        info.setRatingRatio(copilotRating.getRatingRatio());
        info.setRatingLevel(copilotRating.getRatingLevel());
        info.setLevel(levelInfo);
        info.setAvailable(true);

        //评分数少于一定数量
        info.setNotEnoughRating(copilotRating.getRatingUsers().size() > 5);


        try {
            info.setContent(mapper.writeValueAsString(copilot));
        } catch (JsonProcessingException e) {
            log.error("json序列化失败", e);
        }
        return info;
    }


    /**
     * 获取用户唯一标识符<br/>
     * 如果未登录获取ip <br/>
     * 如果已登录获取id
     *
     * @param loginUser LoginUser
     * @return 用户标识符
     */
    private String getUserId(LoginUser loginUser) {
        //TODO  此处更换为调用IPUtil工具类
        String id = request.getRemoteAddr();
        if (request.getHeader("x-forwarded-for") != null) {
            id = request.getHeader("x-forwarded-for");
        }
        //账户已登录? 获取userId
        if (!ObjectUtils.isEmpty(loginUser)) {
            id = loginUser.getMaaUser().getUserId();
        }
        return id;
    }
}