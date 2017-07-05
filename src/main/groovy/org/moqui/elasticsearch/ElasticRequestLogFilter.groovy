/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.elasticsearch

import groovy.transform.CompileStatic
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.action.bulk.BulkRequestBuilder
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.client.Client
import org.elasticsearch.transport.TransportException
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Check authentication and permission for servlets other than MoquiServlet, MoquiFopServlet.
 * Specify permission to check in 'permission' init-param. */
@CompileStatic
class ElasticRequestLogFilter implements Filter {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticRequestLogFilter.class)
    final static String INDEX_NAME = "moqui_http_log"
    final static String DOC_TYPE = "MoquiHttpRequest"

    protected FilterConfig filterConfig = null
    protected ExecutionContextFactoryImpl ecfi = null

    private Client elasticSearchClient = null
    private boolean disabled = false
    final ConcurrentLinkedQueue<Map> requestLogQueue = new ConcurrentLinkedQueue<>()

    ElasticRequestLogFilter() { super() }

    @Override
    void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig

        ecfi = (ExecutionContextFactoryImpl) filterConfig.servletContext.getAttribute("executionContextFactory")
        if (ecfi == null) ecfi = (ExecutionContextFactoryImpl) Moqui.executionContextFactory

        elasticSearchClient = ecfi.getTool("ElasticSearch", Client.class)
        if (elasticSearchClient == null) {
            logger.error("In ElasticRequestLogFilter init could not find ElasticSearch tool")
        } else {
            // check for index exists, create with mapping for log doc if not
            boolean hasIndex = elasticSearchClient.admin().indices().exists(new IndicesExistsRequest(INDEX_NAME)).actionGet().exists
            if (!hasIndex) {
                CreateIndexRequestBuilder cirb = elasticSearchClient.admin().indices().prepareCreate(INDEX_NAME)
                cirb.addMapping(DOC_TYPE, docMapping)
                cirb.execute().actionGet()
            }

            RequestLogQueueFlush rlqf = new RequestLogQueueFlush(this)
            ecfi.scheduledExecutor.scheduleAtFixedRate(rlqf, 15, 5, TimeUnit.SECONDS)
        }
    }

    // TODO: add geoip (see https://www.elastic.co/guide/en/logstash/current/plugins-filters-geoip.html)
    // TODO: add user_agent (see https://www.elastic.co/guide/en/logstash/current/plugins-filters-useragent.html)

    final static Map docMapping = [properties:
        ['@timestamp':[type:'date', format:'epoch_millis'], remote_ip:[type:'ip'], remote_user:[type:'keyword'], server_ip:[type:'ip'],
            request_method:[type:'keyword'], request_scheme:[type:'keyword'], request_host:[type:'keyword'],
            request_path:[type:'text'], request_query:[type:'text'],
            http_version:[type:'half_float'], response:[type:'integer'], bytes:[type:'long'], referrer:[type:'text'], agent:[type:'text']
        ]
    ]

    @Override
    void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        // chain first so response is run
        chain.doFilter(req, resp)

        if (elasticSearchClient == null || disabled) return

        if (!(req instanceof HttpServletRequest) || !(resp instanceof HttpServletResponse)) return
        HttpServletRequest request = (HttpServletRequest) req
        HttpServletResponse response = (HttpServletResponse) resp

        String clientIpAddress = request.getRemoteAddr()
        String forwardedFor = request.getHeader("X-Forwarded-For")
        if (forwardedFor != null && !forwardedFor.isEmpty()) clientIpAddress = forwardedFor.split(",")[0].trim()

        float httpVersion = 0.0
        String protocol = request.getProtocol().trim()
        int psIdx = protocol.indexOf("/")
        if (psIdx > 0) try { httpVersion = Float.parseFloat(protocol.substring(psIdx + 1)) } catch (Exception e) { }

        // TODO: get response size, only way to wrap the response with wrappers for Writer and OutputStream to count size? messy, slow...
        // bytes:0,

        Map reqMap = ['@timestamp':System.currentTimeMillis(), remote_ip:clientIpAddress, remote_user:request.getRemoteUser(),
                server_ip:request.getLocalAddr(),
                request_method:request.getMethod(), request_scheme:request.getScheme(), request_host:request.getServerName(),
                request_path:request.getRequestURI(), request_query:request.getQueryString(),
                http_version:httpVersion, response:response.getStatus(),
                referrer:request.getHeader("Referrer"), agent:request.getHeader("User-Agent")]
        requestLogQueue.add(reqMap)
    }

    @Override
    void destroy() {  }

    static class RequestLogQueueFlush implements Runnable {
        final static int maxCreates = 50
        final ElasticRequestLogFilter filter

        RequestLogQueueFlush(ElasticRequestLogFilter filter) { this.filter = filter }

        @Override synchronized void run() {
            while (filter.requestLogQueue.size() > 0) {
                flushQueue()
            }
        }
        void flushQueue() {
            final ConcurrentLinkedQueue<Map> queue = filter.requestLogQueue
            ArrayList<Map> createList = new ArrayList<>(maxCreates)
            int createCount = 0
            while (createCount < maxCreates) {
                Map message = queue.poll()
                if (message == null) break
                // increment the count and add the message
                createCount++
                createList.add(message)
            }
            int retryCount = 5
            while (retryCount > 0) {
                int createListSize = createList.size()
                if (createListSize == 0) break
                try {
                    // long startTime = System.currentTimeMillis()
                    try {
                        BulkRequestBuilder bulkBuilder = filter.elasticSearchClient.prepareBulk()
                        for (int i = 0; i < createListSize; i++) {
                            Map curMessage = createList.get(i)
                            // logger.warn(curMessage.toString())
                            bulkBuilder.add(filter.elasticSearchClient.prepareIndex(INDEX_NAME, DOC_TYPE, null)
                                    .setSource(curMessage))
                        }
                        BulkResponse bulkResponse = bulkBuilder.execute().actionGet()
                        if (bulkResponse.hasFailures()) {
                            logger.error(bulkResponse.buildFailureMessage())
                        }
                    } catch (TransportException te) {
                        String message = te.getMessage()
                        if (message && message.toLowerCase().contains("stopped")) {
                            filter.disabled = true
                            logger.error("Stopping ElasticSearch HTTP Request logging, transport error: ${te.toString()}")
                        } else {
                            logger.error("Error  logging to ElasticSearch: ${te.toString()}")
                        }
                    } catch (Exception e) {
                        logger.error("Error logging to ElasticSearch: ${e.toString()}")
                    }
                    // logger.warn("Indexed ${createListSize} ElasticSearch log messages in ${System.currentTimeMillis() - startTime}ms")
                    break
                } catch (Throwable t) {
                    logger.error("Error indexing ElasticSearch log messages, retrying (${retryCount}): ${t.toString()}")
                    retryCount--
                }
            }
        }
    }
}