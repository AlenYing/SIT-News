package Service;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.SuggestionBuilder;
import org.elasticsearch.search.suggest.term.TermSuggestion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class SearchEngine {
    private RestHighLevelClient esClient;

    public SearchEngine(RestHighLevelClient esClient) {
        this.esClient = esClient;
    }

    /* Utils */
    private String joinDescription(ArrayList<HashMap<String, String>> paragraphs, int maxLength) {
        StringBuilder result = new StringBuilder();

        for (HashMap<String, String> p : paragraphs) {
            if (p.get("type_").equals("Text")) {
                result.append(p.get("text")).append("\n");
                if (result.length() > maxLength) {
                    return result.substring(0, maxLength);
                }
            }
        }
        return result.toString();
    }

    private String joinDescription(ArrayList<HashMap<String, String>> paragraphs) {
        return joinDescription(paragraphs, Integer.MAX_VALUE);
    }

    /* Function*/
    public SuggestWord suggest(String queryStr) throws IOException {
        SearchRequest request = new SearchRequest("news");
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        SuggestBuilder suggestBuilder = new SuggestBuilder();

        /* Set suggestion fields. */
        SuggestionBuilder titleSuggester = SuggestBuilders.termSuggestion("title").text(queryStr);
        suggestBuilder.addSuggestion("suggestTitle", titleSuggester);
        searchSourceBuilder.suggest(suggestBuilder);

        /* Get response */
        SearchResponse searchResponse = esClient.search(request, RequestOptions.DEFAULT);
        // System.out.println(searchResponse.toString());
        Suggest suggest = searchResponse.getSuggest();
        TermSuggestion termSuggestion = suggest.getSuggestion("suggestTitle");

        /* Convert response. */
        Vector<String> result = new Vector<>();
        for (TermSuggestion.Entry entry : termSuggestion.getEntries()) {
            for (TermSuggestion.Entry.Option option : entry) {
                String suggestText = option.getText().string();
                result.add(suggestText);
            }
        }
        return new SuggestWord(result);
    }

    public QueryResults query(String queryStr, int offset, int count, String... fieldNames) throws IOException {
        /* Init */
        SearchRequest request = new SearchRequest("news");
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

        /* Set query options */
        searchSourceBuilder.query(QueryBuilders.multiMatchQuery(queryStr, fieldNames)
                .fuzziness(Fuzziness.ZERO)
                .field("title", 3)); // Set boost.
        searchSourceBuilder.from(offset);
        searchSourceBuilder.size(count);

        /* Set fetching fields. */
        String[] includeFields = new String[]{"title", "datetime", "author", "content", "url"};
        searchSourceBuilder.fetchSource(includeFields, new String[]{});

        request.source(searchSourceBuilder);
        SearchResponse searchResponse = esClient.search(request, RequestOptions.DEFAULT);

        /* Retrieve search results */
        SearchHits hits = searchResponse.getHits();
        ArrayList<ResultItem> results = new ArrayList<>();
        for (SearchHit hit : hits.getHits()) {

            ResultItem item = new ResultItem();
            Map<String, Object> fields = hit.getSourceAsMap();

            item.docId = hit.getId();
            item.date = fields.get("datetime").toString();
            item.title = fields.get("title").toString();
            item.url = fields.get("url").toString();
            item.author = fields.get("author").toString();

            ArrayList<HashMap<String, String>> content = (ArrayList<HashMap<String, String>>) fields.get("content");
            item.description = joinDescription(content, 100) + "...";
            results.add(item);
        }

        /* Construct query results */
        QueryResults queryResults = new QueryResults();
        queryResults.costTime = searchResponse.getTook().secondsFrac();
        queryResults.hits = results.size();
        queryResults.results = results;

        return queryResults;
    }

    public QueryResults queryNormally(String queryStr, int offset, int count) throws IOException {
        return query(queryStr, offset, count, "title", "author", "content");
    }

    public QueryResults queryByAuthor(String author, int offset, int count) throws IOException {
        return query(author, offset, count, "author");
    }

    public QueryResults queryByTitle(String title, int offset, int count) throws IOException {
        return query(title, offset, count, "title");
    }

    public ResultItem get(String doc_id) throws IOException {
        /* Init */
        SearchRequest request = new SearchRequest("news");
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

        /* Set query options */
        searchSourceBuilder.query(QueryBuilders.termQuery("_id", doc_id));
        request.source(searchSourceBuilder);

        SearchResponse searchResponse = esClient.search(request, RequestOptions.DEFAULT);

        /* Retrieve search result */
        ResultItem item = new ResultItem();

        try {
            SearchHit hit = searchResponse.getHits().getAt(0);
            Map<String, Object> fields = hit.getSourceAsMap();

            item.docId = hit.getId();
            item.date = fields.get("datetime").toString();
            item.title = fields.get("title").toString();
            item.url = fields.get("url").toString();
            item.author = fields.get("author").toString();

            ArrayList<HashMap<String, String>> content = (ArrayList<HashMap<String, String>>) fields.get("content");
            item.description = joinDescription(content).replace("\n", "<br>");
        } catch (Exception e) {
            // Do nothing
        }
        return item;
    }

    public static class ResultItem {
        public String docId;
        public String title;
        public String url;
        public String author;
        public String date;
        public String description;
    }

    public static class QueryResults {
        public double costTime;
        public int hits;
        public ArrayList<ResultItem> results;
    }

    public static class SuggestWord {
        public int count;
        public Vector<String> keywords;

        public SuggestWord(Vector<String> keywords) {
            this.keywords = keywords;
            this.count = keywords.size();
        }
    }
}
