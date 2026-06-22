package com.example.javaagentmvp.admissionworkflow;

/** Default Gaokao admission year when IR / tool args omit {@code year}. */
public final class DefaultAdmissionYear {

    public static final int VALUE = 2025;

    private DefaultAdmissionYear() {
    }

    public static int resolve(Integer year) {
        return year != null ? year : VALUE;
    }
}
