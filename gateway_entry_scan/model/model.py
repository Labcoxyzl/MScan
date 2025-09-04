from langchain_openai import ChatOpenAI
import config


def new_llm():
    llm = ChatOpenAI(api_key=config.OPENAI_API_KEY, base_url=config.OPENAI_BASE_URL, model="gpt-4.1")
    return llm