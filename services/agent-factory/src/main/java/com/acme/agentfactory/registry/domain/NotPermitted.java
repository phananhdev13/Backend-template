package com.acme.agentfactory.registry.domain;

import com.acme.kernel.error.DomainException;
import com.acme.kernel.error.ErrorKind;
import java.util.Map;

/** The caller is authenticated but not allowed to do this. See P-120. */
public final class NotPermitted extends DomainException {

    private static final long serialVersionUID = 1L;

    public NotPermitted(String code, String message, Map<String, Object> details) {
        super(ErrorKind.FORBIDDEN, code, message, details);
    }
}
