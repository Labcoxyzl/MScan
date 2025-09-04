from model import new_llm
from pydantic import BaseModel, Field
from langchain_core.prompts import ChatPromptTemplate, FewShotChatMessagePromptTemplate
from prompt.example import GATEWAY_EXAMPLE
from prompt.system import GATEWAY_ENTRY_SCAN_TASK
import os

if __name__ == "__main__":

    example_prompt = ChatPromptTemplate.from_messages(
        [
            (
                "human",
                GATEWAY_ENTRY_SCAN_TASK,
            ),
            ("ai", "{output}")
        ]
    )

    few_shot_prompt = FewShotChatMessagePromptTemplate(
        examples=GATEWAY_EXAMPLE,
        example_prompt=example_prompt
    )

    final_prompt = ChatPromptTemplate.from_messages(
        [
            few_shot_prompt,
            (
                "human",
                GATEWAY_ENTRY_SCAN_TASK
            )
        ]
    )

    class GatewayConfig(BaseModel):
        external_entries: list[str] = Field(description="A list of all user-accessible (externally exposed) entry point paths.")
        internal_entries: list[str] = Field(description="A list of all internal (not externally exposed) entry point paths.")

    llm = new_llm().with_structured_output(GatewayConfig)

    chain = final_prompt | llm


    for k in os.listdir("input"):
        with open("input/" + k, encoding="utf-8") as f:
            data = f.read()
        r:GatewayConfig = chain.invoke({"input": data})
        print(r)
        with open("output/" +  k.split(".")[0] + ".json", encoding="utf-8", mode="w+") as f:
            f.write(r.model_dump_json())
        print("finish: " + k)
