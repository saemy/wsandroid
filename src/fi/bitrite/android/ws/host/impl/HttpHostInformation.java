package fi.bitrite.android.ws.host.impl;

import fi.bitrite.android.ws.api.RestClient;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.util.GlobalInfo;
import fi.bitrite.android.ws.util.http.HttpException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;


/**
 * Gets host information based on host ID.
 */
public class HttpHostInformation extends RestClient {

    public Host getHostInformation(int uid) throws JSONException, IOException, URISyntaxException {
        // TODO: This usage is DEPRECATED. We're supposed to use services/rest/user/<uid>
        String simpleUrl = GlobalInfo.warmshowersBaseUrl + "/user/" + Integer.toString(uid) + "/json";
        JSONObject jsonObject = get(simpleUrl);

        try {
            JSONArray hostJsonArray = jsonObject.getJSONArray("users");
            JSONObject hostJson = hostJsonArray.getJSONObject(0);
            Host host = Host.CREATOR.parse(hostJson.getJSONObject("user"));

            if (host.getFullname().isEmpty()) {
                throw new HttpException("Could not parse JSON");
            }

            return host;
        } catch (JSONException e) {
            throw new HttpException(e);
        }
    }
}
