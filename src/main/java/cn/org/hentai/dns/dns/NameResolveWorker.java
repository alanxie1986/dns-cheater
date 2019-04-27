package cn.org.hentai.dns.dns;

import cn.org.hentai.dns.cache.CacheManager;
import cn.org.hentai.dns.dns.coder.SimpleMessageDecoder;
import cn.org.hentai.dns.dns.coder.SimpleMessageEncoder;
import cn.org.hentai.dns.dns.entity.*;
import cn.org.hentai.dns.util.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Created by matrixy on 2019/4/19.
 */
public class NameResolveWorker extends Thread
{
    static Logger logger = LoggerFactory.getLogger(NameResolveWorker.class);

    NameServer nameServer;
    ArrayList<Question> questions = new ArrayList(100);
    ArrayList answers = new ArrayList();

    public NameResolveWorker(NameServer nameServer)
    {
        this.nameServer = nameServer;
    }

    private void getAndResolve() throws Exception
    {
        Request request = this.nameServer.takeRequest();
        if (request == null) return;

        // 消息包解码
        Message msg = SimpleMessageDecoder.decode(request.packet);
        logger.debug("decode: TransactionId: {}, Flags: {}, Questions: {}, AnswerRRs: {}, AuthorityRRs: {}, AdditionalRRs: {}", msg.transactionId, Integer.toBinaryString(msg.flags), msg.questions, msg.answerRRs, msg.authorityRRs, msg.additionalRRs);
        logger.debug("decode flags: QR: {}, OP: {}, AA: {}, TC: {}, RD: {}, RA: {}, RCode: {}", msg.isQuestion(), msg.getOperateType(), msg.isAuthorityAnswer(), msg.isTruncateable(), msg.isRecursiveExpected(), msg.isRecursively(), msg.getReturnCode());

        if (msg.isQuestion() == false)
        {
            logger.debug("skip current question: " + ByteUtils.toString(request.packet.nextBytes()));
            logger.error("要不是我读错了，嗯，只有可能是读错了。。。");
            // return;
        }

        // 遍历每一个要查询的域名
        request.packet.seek(12);
        int len = 0;
        questions.clear();
        for (int i = 0; i < msg.questions; i++)
        {
            StringBuilder name = new StringBuilder(64);
            while ((len = request.packet.nextByte() & 0xff) > 0)
            {
                name.append(new String(request.packet.nextBytes(len)));
                name.append('.');
            }
            int queryType = request.packet.nextShort() & 0xffff;
            int queryClass = request.packet.nextShort() & 0xffff;
            questions.add(new Question(name.toString(), queryType));
        }

        // 依次处理，一般来说，都是单个查询的吧，只有自己写程序才有可能会有批量查询的情况
        if (questions.size() > 1) throw new RuntimeException("multiple name resolve unsupported");
        CacheManager cacheManager = CacheManager.getInstance();
        for (Question question : questions)
        {
            logger.debug("resolve: name = {}, type = {}", question.name, question.type);
            if (question.type != Message.TYPE_A && question.type != Message.TYPE_AAAA)
            {
                logger.error("unsupported query type: {}", question.type);
                continue;
            }
            ResourceRecord[] answers = cacheManager.get(question.name);
            // 规则引擎决定了什么东西？
            if (answers == null)
            {
                // 交给递归解析线程去上游服务器解析
            }
            else
            {
                // 返回结果
                logger.debug("resolved: name = {}, answer = {}", question.name, answers[0].ipv4);
                byte[] resp = SimpleMessageEncoder.encode(msg, question, answers);
                this.nameServer.putResponse(new Response(request.remoteAddress, resp));
            }
        }
    }

    public void run()
    {
        while (!this.isInterrupted())
        {
            try
            {
                getAndResolve();
            }
            catch(Exception e)
            {
                logger.error("domain name resolve error", e);
            }
        }
    }
}