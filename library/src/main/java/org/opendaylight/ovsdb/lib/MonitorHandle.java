/*
 *
 *  * Copyright (C) 2014 EBay Software Foundation
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 *  * and is available at http://www.eclipse.org/legal/epl-v10.html
 *  *
 *  * Authors : Ashwin Raveendran
 *
 */

package org.opendaylight.ovsdb.lib;

import java.io.Serializable;

public class MonitorHandle implements Serializable {
    String id;

    public MonitorHandle(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
