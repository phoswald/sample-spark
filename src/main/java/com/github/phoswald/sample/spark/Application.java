package com.github.phoswald.sample.spark;

import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.put;
import static spark.Spark.staticFiles;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.phoswald.sample.ConfigProvider;
import com.github.phoswald.sample.sample.EchoRequest;
import com.github.phoswald.sample.sample.EchoResponse;
import com.github.phoswald.sample.sample.SampleController;
import com.github.phoswald.sample.sample.SampleResource;
import com.github.phoswald.sample.task.TaskController;
import com.github.phoswald.sample.task.TaskEntity;
import com.github.phoswald.sample.task.TaskRepository;
import com.github.phoswald.sample.task.TaskResource;
import com.google.gson.Gson;
import com.thoughtworks.xstream.XStream;

import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.Route;

public class Application {

    private static final Logger logger = LogManager.getLogger();
    private static final ConfigProvider config = new ConfigProvider();
    private static final int port = Integer.parseInt(config.getConfigProperty("app.http.port").orElse("8080"));
    private static final XStream xstream = new XStream();
    private static final Gson gson = new Gson();
    private static final EntityManagerFactory emf = createEntityManagerFactory();

    static {
        xstream.allowTypes(new Class[] { EchoRequest.class, EchoResponse.class });
        xstream.alias("EchoRequest", EchoRequest.class);
        xstream.alias("EchoResponse", EchoResponse.class);
    }

    public static void main(String[] args) {
        logger.info("sample-spark is starting, port=" + port);
        port(port);
        staticFiles.location("/resources");
        get("/app/rest/sample/time", (req, res) -> new SampleResource().getTime());
        get("/app/rest/sample/config", (req, res) -> new SampleResource().getConfig());
        post("/app/rest/sample/echo-xml", createXmlHandler(EchoRequest.class, reqBody -> new SampleResource().postEcho(reqBody)));
        post("/app/rest/sample/echo-json", createJsonHandler(EchoRequest.class, reqBody -> new SampleResource().postEcho(reqBody)));
        get("/app/pages/sample", createHtmlHandler(() -> new SampleController().getSamplePage()));
        get("/app/rest/tasks", createJsonHandler(() -> createTaskResource().getTasks()));
        post("/app/rest/tasks", createJsonHandler(TaskEntity.class, req -> createTaskResource().postTasks(req)));
        get("/app/rest/tasks/:id", createJsonHandlerEx(req -> createTaskResource().getTask(req.params("id"))));
        put("/app/rest/tasks/:id", createJsonHandlerEx(TaskEntity.class, (req, reqBody) -> createTaskResource().putTask(req.params("id"), reqBody)));
        delete("/app/rest/tasks/:id", createJsonHandlerEx(req -> createTaskResource().deleteTask(req.params("id"))));
        get("/app/pages/tasks", createHtmlHandler(() -> createTaskController().getTasksPage()));
        post("/app/pages/tasks", createHtmlFormHandler(form -> createTaskController().postTasksPage(form.get("title").value(), form.get("description").value())));
        get("/app/pages/tasks/:id", createHtmlHandlerEx(req -> createTaskController().getTaskPage(req.params("id"), req.queryParams("action"))));
        post("/app/pages/tasks/:id", createHtmlFormHandlerEx((req, form) -> createTaskController().postTaskPage(req.params("id"), form.get("action").value(), form.get("title").value(), form.get("description").value(), form.get("done").value())));
    }

    // TODO generate 404 if response body is null
    // TODO prevent returning body "null" when not found or deleted

    private static <R> Route createXmlHandler(Class<R> reqClass, Function<R, Object> callback) {
        return (req, res) -> {
            res.type("text/xml");
            return xstream.toXML(callback.apply(reqClass.cast(xstream.fromXML(req.body()))));
        };
    }

    private static <R> Route createJsonHandler(Supplier<Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return gson.toJson(callback.get());
        };
    }

    private static <R> Route createJsonHandlerEx(Function<Request, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return gson.toJson(callback.apply(req));
        };
    }

    private static <R> Route createJsonHandler(Class<R> reqClass, Function<R, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return gson.toJson(callback.apply(gson.fromJson(req.body(), reqClass)));
        };
    }

    private static <R> Route createJsonHandlerEx(Class<R> reqClass, BiFunction<Request, R, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return gson.toJson(callback.apply(req, gson.fromJson(req.body(), reqClass)));
        };
    }

    private static Route createHtmlHandler(Supplier<Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            return callback.get();
        };
    }

    private static Route createHtmlHandlerEx(Function<Request, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            return callback.apply(req);
        };
    }

    private static Route createHtmlFormHandler(Function<QueryParamsMap, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            QueryParamsMap form = req.queryMap();
            return callback.apply(form);
        };
    }

    private static Route createHtmlFormHandlerEx(BiFunction<Request, QueryParamsMap, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            QueryParamsMap form = req.queryMap();
            return sendHtmlOrRedirect(res, callback.apply(req, form));
        };
    }

    private static Object sendHtmlOrRedirect(Response res, Object result) {
        if(result instanceof String && ((String) result).startsWith("REDIRECT:")) { // TODO refactor redirect
        	res.redirect(((String) result).substring(9));
            return null;
        } else {
            res.type("text/html");
            return result;
        }
    }

    private static TaskResource createTaskResource()  {
        return new TaskResource(createTaskRepository());
    }

    private static TaskController createTaskController() {
        return new TaskController(createTaskRepository());
    }

    private static TaskRepository createTaskRepository() {
        return new TaskRepository(emf.createEntityManager()); // TODO review cleanup
    }

    private static EntityManagerFactory createEntityManagerFactory() {
    	Map<String, String> props = new HashMap<>();
    	config.getConfigProperty("app.jdbc.driver"  ).ifPresent(v -> props.put("javax.persistence.jdbc.driver", v));
    	config.getConfigProperty("app.jdbc.url"     ).ifPresent(v -> props.put("javax.persistence.jdbc.url", v));
    	config.getConfigProperty("app.jdbc.username").ifPresent(v -> props.put("javax.persistence.jdbc.user", v));
    	config.getConfigProperty("app.jdbc.password").ifPresent(v -> props.put("javax.persistence.jdbc.password", v));
    	return Persistence.createEntityManagerFactory("taskDS", props);
    }
}
