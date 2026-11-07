package com.lnjecit.knowledge.assistant;

import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** 测试只使用已准备的镜像，辅助容器也不得隐式拉取。 */
public final class LocalImagePullPolicy implements ImagePullPolicy {
    @Override
    public boolean shouldPull(DockerImageName imageName) {
        return false;
    }
}
