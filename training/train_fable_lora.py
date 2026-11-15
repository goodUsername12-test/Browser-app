#!/usr/bin/env python3
"""LoRA specialization script for Qwen/Qwen3.5-0.8B or Qwen/Qwen3-0.6B -> Q4_K_M GGUF."""
import argparse

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="Qwen/Qwen3.5-0.8B", help="Base HF Safetensors model ID")
    parser.add_argument("--dataset", default="fable_train.jsonl", help="JSONL training dataset")
    parser.add_argument("--out", default="fable-specialized-gguf", help="Output folder")
    parser.add_argument("--epochs", type=int, default=3)
    args = parser.parse_args()

    from datasets import load_dataset
    from trl import SFTConfig, SFTTrainer
    from unsloth import FastLanguageModel

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model,
        max_seq_length=2048,
        load_in_4bit=False,
    )
    model = FastLanguageModel.get_peft_model(
        model,
        r=16,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        lora_alpha=32,
        lora_dropout=0.0,
        bias="none",
    )
    ds = load_dataset("json", data_files=args.dataset, split="train")
    ds = ds.map(
        lambda row: {
            "text": tokenizer.apply_chat_template(
                row["messages"], tokenize=False, add_generation_prompt=False
            )
        }
    )
    trainer = SFTTrainer(
        model=model,
        processing_class=tokenizer,
        train_dataset=ds,
        args=SFTConfig(
            output_dir=args.out,
            num_train_epochs=args.epochs,
            per_device_train_batch_size=4,
            gradient_accumulation_steps=4,
            learning_rate=2e-4,
            logging_steps=5,
            dataset_text_field="text",
        ),
    )
    trainer.train()
    model.save_pretrained_gguf(args.out, tokenizer, quantization_method="q4_k_m")
    print(f"Exported Q4_K_M GGUF into {args.out}")

if __name__ == "__main__":
    main()
