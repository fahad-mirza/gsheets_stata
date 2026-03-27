// =============================================================================
// ServiceAccountKey.java - gsheets package
// Maps the Google service account JSON key file format for Gson parsing.
// Google uses snake_case field names which Gson needs to match exactly.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.google.gson.annotations.SerializedName;

class ServiceAccountKey {

    @SerializedName("type")
    String type;

    @SerializedName("client_email")
    String clientEmail;

    @SerializedName("private_key")
    String privateKey;

    @SerializedName("token_uri")
    String tokenUri;

    @SerializedName("project_id")
    String projectId;

    @SerializedName("private_key_id")
    String privateKeyId;

    @SerializedName("client_id")
    String clientId;
}
