### Fork Notes
- Added `Config.java` at `/src/main/java/fdu/secsys/microservice/Config.java` (relevant [issue #1](https://github.com/LFYSec/MScan/issues/1))
- Set default `Config.optionsFile` to `/src/main/resources/options.yaml` in `Starter.java`
- Configured `Starter.java` as the main class in `build.gradle.kts`
- Can be run directly without IDEA using `./gradlew run` (after setting config values in `Starter.java`)
- Added `java-benchmarks` submodule (Tai-e [prerequisite](https://github.com/Labcoxyzl/MScan/blob/main/docs/en/command-line-options.adoc)), can be initialized using
```shell
git submodule update --init --recursive
```

# MScan

## Description
The source code of _Detecting Taint-Style Vulnerabilities in Microservice-Structured Web Applications_.

```
@inproceedings{liu2025detecting,
  title={Detecting Taint-Style Vulnerabilities in Microservice-Structured Web Applications},
  author={Liu, Fengyu and Zhang, Yuan and Chen, Tian and Shi, Youkun and Yang, Guangliang and Lin, Zihan and Yang, Min and He, Junyao and Li, Qi},
  booktitle={2025 IEEE Symposium on Security and Privacy (SP)},
  pages={972--990},
  year={2025},
  organization={IEEE}
}
```

## Install
First, clone the project.
```shell
git clone ...
```
Then open it in [IDEA](https://www.jetbrains.com/idea/) and set the project SDK to JDK 17 in the project settings.

## Step1. Entry Extraction
Before extracting, make sure you have Python 3.11 installed, and install the required dependencies:
```shell
cd gateway_entry_scan && pip install -r requirements.txt
```
Next, place the gateway YAML file of your target project into the `gateway_entry_scan/input` folder.

For example, `gateway_entry_scan/input/youlai-mall.yaml`.

Fill your OPENAI_API_KEY in `gateway_entry_scan/config/llm.py`.
```python
OPENAI_API_KEY = "sk-xxxxxxxxxxx"
```
Run main.py to start extraction.
```shell
python main.py
```
Then get the entry rule at `gateway_entry_scan/output/<your_project_name>.json`.

For example, `gateway_entry_scan/output/youlai-mall.json`.

Then move the rule file to src/main/resources/entry
```shell
mv gateway_entry_scan/output/<your_project_name>.json src/main/resources/entry
```

## Step2. Analysis
Before analysis, you should prepare two folders.

The first folder is the JAR folder of your target project, containing the packaged JAR files and dependencies for each microservice.

For example
```
jars/
    - youlai-gateway.jar
    - youlai-auth.jar
    - oms-api.jar
    - oms-boot.jar
    - pms-api.jar
    - pms-boot.jar
    - ...
```
The second folder is a temporary working directory for the analysis, typically located at `/tmp/<your_project_name>`.

Then change the analysis config in `src/main/java/Starter`.
```java
public class Starter {
    public static void main(String[] args) throws IOException {
        Timer.runAndCount(() -> {
            Config.name = "<your_project_name>"; // MUST! the same as the project name in rule file: <your_project_name>.json, e.g. in the project youlai-mall, it is youlai-mall
            Config.classpathKeywords = new String[]{"com.example."}; // package name keyword of your target project to match core classes, e.g. in youlai-mall, it can be .youlai.
            Config.jarPath = "./jars"; // path to the first folder, jar folder
            Config.targetPath = "/tmp/<your_project_name>"; // path to the second folder, temp working folder
            ...
        }, Config.name);
    }
}
```
Then run the analysis in IDEA by executing the Starter class as the main class, specifying a large maximum Java VM memory, such as
```
-Xmx40g
```
When the analysis is complete, you can find the taint flow results in `output/microservice-taint-flows.txt`.

For example, in the case of youlai-mall, the output may look like:
```
2 TaintFlow{<com.youlai.mall.pms.controller.app.SpuController: com.youlai.common.result.PageResult listPagedSpuForApp(com.youlai.mall.pms.model.query.SpuPageQuery)>/0 -> <com.youlai.mall.pms.service.impl.SpuServiceImpl: com.baomidou.mybatisplus.core.metadata.IPage listPagedSpuForApp(com.youlai.mall.pms.model.query.SpuPageQuery)>[8@L75] $r5 = invokeinterface $r4.listPagedSpuForApp($r0, queryParams)/1 --- VUL_ID:SQLI_Mybatis_Xml}
```
