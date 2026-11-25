package com.smartsahay.servlet;

import com.google.gson.JsonParseException;
import com.smartsahay.dao.RuleDAO;
import com.smartsahay.dao.SchemeDAO;
import com.smartsahay.model.EligibilityRequest;
import com.smartsahay.model.EligibilityResult;
import com.smartsahay.model.Scheme;
import com.smartsahay.model.SchemeRule;
import com.smartsahay.service.EligibilityService;
import com.smartsahay.service.IvrService;
import com.smartsahay.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulated-IVR endpoints used by the keypad demo page.
 *
 *   GET  /api/ivr/menu                              main menu (1 = Central, 2 = Karnataka state)
 *   GET  /api/ivr/menu?group=1&page=0               schemes of a group, 8 per page (9 = more, 0 = back)
 *   GET  /api/ivr/scheme?group=1&page=0&digits=3    spoken summary of one scheme + structured card data
 *   POST /api/ivr/eligibility                       JSON body of keypad answers -> spoken result + matched list
 */
@WebServlet("/api/ivr/*")
public class IvrServlet extends HttpServlet {

    private final SchemeDAO schemeDao = new SchemeDAO();
    private final RuleDAO ruleDao = new RuleDAO();
    private final EligibilityService eligibilityService = new EligibilityService();
    private final IvrService ivr = new IvrService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (!"/menu".equals(path) && !"/scheme".equals(path)) {
            JsonUtil.error(resp, 404, "Not found");
            return;
        }
        try {
            List<Scheme> all = schemeDao.findAll(null, null, null);
            if ("/menu".equals(path)) {
                menu(req, resp, all);
            } else {
                scheme(req, resp, all);
            }
        } catch (SQLException e) {
            getServletContext().log("IVR menu lookup failed", e);
            JsonUtil.error(resp, 500, "Internal server error");
        }
    }

    private void menu(HttpServletRequest req, HttpServletResponse resp, List<Scheme> all) throws IOException {
        if (req.getParameter("group") == null) {
            speak(resp, ivr.mainMenu(all));
            return;
        }
        IvrService.Menu sub = ivr.schemeMenu(all, intParam(req, "group", -1), intParam(req, "page", 0));
        if (sub == null) {
            IvrService.Menu main = ivr.mainMenu(all);
            main.setText("Sorry, that option is not available. " + main.getText());
            speak(resp, main);
            return;
        }
        speak(resp, sub);
    }

    private void scheme(HttpServletRequest req, HttpServletResponse resp, List<Scheme> all)
            throws IOException, SQLException {
        Scheme picked = ivr.pick(all, intParam(req, "group", -1), intParam(req, "page", 0), req.getParameter("digits"));
        if (picked == null) {
            speak(resp, ivr.invalidKeySpeech());
            return;
        }
        Scheme detail = schemeDao.findBySlug(picked.getSlug());
        if (detail == null) {
            speak(resp, ivr.schemeNotFoundSpeech());
            return;
        }

        Map<String, Object> payload = basePayload(ivr.schemeSpeech(detail), "SCHEME_INFO");
        payload.put("scheme", ivr.summarizeScheme(detail, true));
        JsonUtil.send(resp, 200, payload);
    }

    private static int intParam(HttpServletRequest req, String name, int fallback) {
        String v = req.getParameter(name);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"/eligibility".equals(req.getPathInfo())) {
            JsonUtil.error(resp, 404, "Not found");
            return;
        }
        IvrService.Answers answers;
        try {
            answers = JsonUtil.read(req, IvrService.Answers.class);
        } catch (JsonParseException e) {
            JsonUtil.error(resp, 400, "Request body is not valid JSON");
            return;
        }
        if (ivr.validate(answers) != null) {
            speak(resp, IvrService.BAD_INPUT);
            return;
        }
        EligibilityRequest request = ivr.toRequest(answers);
        try {
            List<Scheme> schemes = schemeDao.findAll(null, null, null);
            Map<Integer, SchemeRule> rules = ruleDao.findAll();
            EligibilityResult result = eligibilityService.check(request, schemes, rules);

            Map<String, Object> payload = basePayload(ivr.eligibilitySpeech(result), "ELIGIBILITY_RESULTS");
            payload.put("results", ivr.summarizeEligibility(result));
            payload.put("farmer_inputs", ivr.formatAnswers(answers));
            JsonUtil.send(resp, 200, payload);
        } catch (SQLException e) {
            getServletContext().log("IVR eligibility failed", e);
            JsonUtil.error(resp, 500, "Internal server error");
        }
    }

    private Map<String, Object> basePayload(String text, String mode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gather_prompt", Map.of("text", text));
        map.put("max_input_digits", 1);
        map.put("mode", mode);
        return map;
    }

    private void speak(HttpServletResponse resp, String text) throws IOException {
        JsonUtil.send(resp, 200, basePayload(text, "MENU"));
    }

    private void speak(HttpServletResponse resp, IvrService.Menu menu) throws IOException {
        Map<String, Object> payload = basePayload(menu.getText(), "MENU");
        payload.put("level", menu.getLevel());
        payload.put("options", menu.getOptions());
        JsonUtil.send(resp, 200, payload);
    }
}