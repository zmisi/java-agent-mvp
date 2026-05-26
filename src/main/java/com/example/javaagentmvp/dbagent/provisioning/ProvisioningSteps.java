package com.example.javaagentmvp.dbagent.provisioning;

import java.util.List;

public final class ProvisioningSteps {

    public static final String VALIDATE_INPUT = "VALIDATE_INPUT";
    public static final String SSH_CONNECT = "SSH_CONNECT";
    public static final String DETECT_OS = "DETECT_OS";
    public static final String CHECK_PG_VERSION = "CHECK_PG_VERSION";
    public static final String INSTALL_PG18 = "INSTALL_PG18";
    public static final String TUNE_MEMORY = "TUNE_MEMORY";
    public static final String CHECK_DISK = "CHECK_DISK";
    public static final String CREATE_DATABASE = "CREATE_DATABASE";
    public static final String INSTALL_EXTENSIONS = "INSTALL_EXTENSIONS";
    public static final String VERIFY_CONNECTION = "VERIFY_CONNECTION";
    public static final String COMPLETE = "COMPLETE";

    public static final List<String> ORDER = List.of(
            VALIDATE_INPUT,
            SSH_CONNECT,
            DETECT_OS,
            CHECK_PG_VERSION,
            INSTALL_PG18,
            TUNE_MEMORY,
            CHECK_DISK,
            CREATE_DATABASE,
            INSTALL_EXTENSIONS,
            VERIFY_CONNECTION,
            COMPLETE);

    private ProvisioningSteps() {
    }
}
